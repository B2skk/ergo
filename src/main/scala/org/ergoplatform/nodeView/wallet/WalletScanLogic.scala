package org.ergoplatform.nodeView.wallet

import org.ergoplatform.http.api.ApplicationEntities.ApplicationIdWrapper
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.ErgoContext
import org.ergoplatform.nodeView.state.ErgoStateContext
import org.ergoplatform.nodeView.wallet.ErgoWalletActor.WalletVars
import org.ergoplatform.nodeView.wallet.IdUtils.{EncodedBoxId, decodedBoxId, encodedBoxId}
import org.ergoplatform.nodeView.wallet.persistence.{OffChainRegistry, WalletRegistry}
import org.ergoplatform.nodeView.wallet.scanning.ExternalApplication
import org.ergoplatform.settings.{Constants, LaunchParameters}
import org.ergoplatform.wallet.Constants.{ApplicationId, MiningRewardsAppId, PaymentsAppId}
import org.ergoplatform.wallet.boxes.{BoxCertainty, TrackedBox}
import org.ergoplatform.wallet.interpreter.ErgoProvingInterpreter
import org.ergoplatform.wallet.protocol.context.TransactionContext
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, UnsignedErgoLikeTransaction, UnsignedInput}
import scorex.util.{ModifierId, ScorexLogging}
import sigmastate.interpreter.ContextExtension
import sigmastate.utxo.CostTable

/**
  * Functions which do scan boxes, transactions and blocks to find boxes which do really belong to wallet's keys.
  */
object WalletScanLogic extends ScorexLogging {

  /**
    * Tries to prove the given box in order to define whether it could be spent by this wallet.
    *
    * todo: currently used only to decide that a box with mining rewards could be spent, do special method for that?
    */
  private def resolve(box: ErgoBox,
                      proverOpt: Option[ErgoProvingInterpreter],
                      stateContext: ErgoStateContext,
                      height: Int): Boolean = {
    val testingTx = UnsignedErgoLikeTransaction(
      IndexedSeq(new UnsignedInput(box.id)),
      IndexedSeq(new ErgoBoxCandidate(1L, Constants.TrueLeaf, creationHeight = height))
    )

    val transactionContext = TransactionContext(IndexedSeq(box), IndexedSeq(), testingTx, selfIndex = 0)
    val context = new ErgoContext(stateContext, transactionContext, ContextExtension.empty,
      LaunchParameters.maxBlockCost, CostTable.interpreterInitCost)

    proverOpt.flatMap(_.prove(box.ergoTree, context, testingTx.messageToSign).toOption).isDefined
  }

  def scanBlockTransactions(registry: WalletRegistry,
                            offChainRegistry: OffChainRegistry,
                            stateContext: ErgoStateContext,
                            walletVars: WalletVars,
                            height: Int,
                            blockId: ModifierId,
                            transactions: Seq[ErgoTransaction]): (WalletRegistry, OffChainRegistry) = {

    //todo: replace with Bloom filter?
    val previousBoxIds = registry.walletUnspentBoxes().map(tb => encodedBoxId(tb.box.id))

    val resolvedBoxes = registry.uncertainBoxes(MiningRewardsAppId).flatMap { tb =>
      //todo: more efficient resolving, just by using height
      val spendable = resolve(tb.box, walletVars.proverOpt, stateContext, height)
      if (spendable) Some(tb.copy(applicationStatuses = Map(PaymentsAppId -> BoxCertainty.Certain))) else None
    }

    //input tx id, input box id, tracked box
    type InputData = Seq[(ModifierId, EncodedBoxId, TrackedBox)]
    //outputs, input ids, related transactions
    type ScanResults = (Seq[TrackedBox], InputData, Seq[WalletTransaction])
    val initialScanResults: ScanResults = (resolvedBoxes, Seq.empty, Seq.empty)

    val scanRes = transactions.foldLeft((initialScanResults, previousBoxIds)) { case ((scanResults, accBoxIds), tx) =>
      val txInputIds = tx.inputs.map(x => encodedBoxId(x.boxId))
      val myOutputs = extractWalletOutputs(tx, Some(height), walletVars)

      val boxIds: Seq[EncodedBoxId] = accBoxIds ++ myOutputs.map(x => EncodedBoxId @@ x.boxId)
      val spendingInputIds = txInputIds.filter(x => boxIds.contains(x))

      if (myOutputs.nonEmpty || spendingInputIds.nonEmpty) {
        val spentBoxes = spendingInputIds.map { inpId =>
          registry.getBox(decodedBoxId(inpId))
            .orElse(scanResults._1.find(tb => tb.box.id.sameElements(decodedBoxId(inpId)))).get //todo: .get
        }

        // Applications related to the transaction
        val walletAppIds = (spentBoxes ++ myOutputs).flatMap(_.applicationStatuses.keys).toSet
        val wtx = WalletTransaction(tx, height, walletAppIds.toSeq)

        val newRel = (scanResults._2: InputData) ++ spendingInputIds.zip(spentBoxes).map(t => (tx.id, t._1, t._2))
        (scanResults._1 ++ myOutputs, newRel, scanResults._3 :+ wtx) -> boxIds
      } else {
        scanResults -> accBoxIds
      }
    }._1

    val outputs = scanRes._1
    val inputs = scanRes._2
    val affectedTransactions = scanRes._3

    // function effects: updating registry and offchainRegistry datasets
    registry.updateOnBlock(outputs, inputs, affectedTransactions)(blockId, height)

    //data needed to update the offchain-registry
    val walletUnspent = registry.walletUnspentBoxes()
    val newOnChainIds = outputs.map(x => encodedBoxId(x.box.id))
    val updatedOffchainRegistry = offChainRegistry.updateOnBlock(height, walletUnspent, newOnChainIds)

    registry -> updatedOffchainRegistry
  }


  /**
    * Extracts all outputs which contain tracked bytes from the given transaction.
    */
  def extractWalletOutputs(tx: ErgoTransaction,
                           inclusionHeight: Option[Int],
                           walletVars: WalletVars): Seq[TrackedBox] = {

    val trackedBytes: Seq[Array[Byte]] = walletVars.trackedBytes
    val miningScriptsBytes: Seq[Array[Byte]] = walletVars.miningScriptsBytes
    val externalApplications: Seq[ExternalApplication] = walletVars.externalApplications

    tx.outputs.flatMap { bx =>
      val appsTriggered = externalApplications.filter(_.trackingRule.filter(bx))
        .map(app => app.appId -> app.initialCertainty)
        .toMap

      val boxScript = bx.propositionBytes

      val statuses: Map[ApplicationId, BoxCertainty] = if (walletVars.filter.lookup(boxScript)) {

        val miningIncomeTriggered = miningScriptsBytes.exists(ms => boxScript.sameElements(ms))

        //tweak for tests
        lazy val miningStatus: (ApplicationId, BoxCertainty) = if (walletVars.settings.miningRewardDelay > 0) {
          MiningRewardsAppId -> BoxCertainty.Certain
        } else {
          PaymentsAppId -> BoxCertainty.Certain
        }

        val prePaymentStatuses = if (miningIncomeTriggered) appsTriggered + miningStatus else appsTriggered

        if (prePaymentStatuses.nonEmpty) {
          //if other applications intercept the box, it is not being tracked by the payments app
          prePaymentStatuses
        } else {
          val paymentsTriggered = trackedBytes.exists(bs => boxScript.sameElements(bs))

          if (paymentsTriggered) {
            Map(PaymentsAppId -> BoxCertainty.Certain)
          } else {
            Map.empty
          }
        }
      } else {
        appsTriggered
      }

      if (statuses.nonEmpty) {
        val tb = TrackedBox(tx.id, bx.index, inclusionHeight, None, None, bx, statuses)
        log.debug("New tracked box: " + tb.boxId)
        Some(tb)
      } else {
        None
      }
    }
  }

}
