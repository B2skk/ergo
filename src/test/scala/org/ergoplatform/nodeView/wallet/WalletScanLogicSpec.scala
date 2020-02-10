package org.ergoplatform.nodeView.wallet

import org.ergoplatform.nodeView.wallet.ErgoWalletActor.WalletVars
import org.ergoplatform.settings.LaunchParameters
import org.ergoplatform.utils.{ErgoPropertyTest, WalletTestOps}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import WalletScanLogic.{extractWalletOutputs, scanBlockTransactions}
import org.ergoplatform.db.DBSpec
import org.ergoplatform.{ErgoBoxCandidate, Input}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.wallet.persistence.{OffChainRegistry, WalletRegistry}
import org.ergoplatform.wallet.Constants
import org.ergoplatform.wallet.boxes.BoxCertainty
import org.scalacheck.Gen
import sigmastate.Values.{ErgoTree, FalseLeaf}

import scala.util.Random

class WalletScanLogicSpec extends ErgoPropertyTest with DBSpec with WalletTestOps {

  private case class TrackedTransaction(tx: ErgoTransaction,
                                        payments: List[ErgoTree],
                                        paymentValues: List[Int],
                                        miningRewards: List[ErgoTree],
                                        miningRewardValues: List[Int])

  private implicit val verifier: ErgoInterpreter = ErgoInterpreter(LaunchParameters)
  private val prover = defaultProver
  private val monetarySettings = initSettings.chainSettings.monetary.copy(minerRewardDelay = 720)
  private val s = initSettings.copy(chainSettings = initSettings.chainSettings.copy(monetary = monetarySettings))
  private val walletVars = WalletVars(Some(prover), Seq.empty)(settings = s)

  private val pubkeys = walletVars.trackedPubKeys
  private val miningScripts = walletVars.miningScripts

  private def paymentsGen: Gen[List[ErgoTree]] = Gen.listOf(Gen.oneOf(pubkeys.map(_.toSigmaProp: ErgoTree)))

  private def miningRewardsGen: Gen[List[ErgoTree]] = Gen.listOf(Gen.oneOf(miningScripts))

  private def nonTrackablePaymentsGen: Gen[List[ErgoTree]] = Gen.nonEmptyListOf(Gen.const(FalseLeaf.toSigmaProp))

  private def trackedTransactionGen: Gen[TrackedTransaction] = {
    for {
      payments <- paymentsGen
      miningRewards <- miningRewardsGen
      nonTrackablePayments <- nonTrackablePaymentsGen
    } yield {
      def valueGen(): Int = Random.nextInt(1000) + 100

      val paymentValues = payments.map(_ => valueGen())
      val miningRewardValues = miningRewards.map(_ => valueGen())
      val nonTrackableValues = nonTrackablePayments.map(_ => valueGen())
      val outs = (payments ++ miningRewards ++ nonTrackablePayments)
        .zip(paymentValues ++ miningRewardValues ++ nonTrackableValues)
        .map { case (script, value) => new ErgoBoxCandidate(value, script, creationHeight = 1) }
        .toIndexedSeq

      val tx = new ErgoTransaction(fakeInputs, IndexedSeq.empty, outs)

      TrackedTransaction(tx, payments, paymentValues, miningRewards, miningRewardValues)
    }
  }

  property("extractWalletOutputs properly extracts") {
    val height = Random.nextInt(200) - 100
    val inclusionHeightOpt = if (height <= 0) None else Some(height)

    forAll(trackedTransactionGen) { trackedTransaction =>
      val foundBoxes = extractWalletOutputs(trackedTransaction.tx, inclusionHeightOpt, walletVars)
      foundBoxes.length shouldBe (trackedTransaction.payments.length + trackedTransaction.miningRewards.length)
      foundBoxes.map(_.inclusionHeightOpt).forall(_ == inclusionHeightOpt) shouldBe true
      foundBoxes.map(_.value).sum shouldBe (trackedTransaction.paymentValues ++ trackedTransaction.miningRewardValues).sum
      foundBoxes.forall(tb => if (trackedTransaction.payments.contains(tb.box.ergoTree)) {
        tb.certain(Constants.PaymentsAppId).get == BoxCertainty.Certain &&
          tb.applicationStatuses == Map(Constants.PaymentsAppId -> BoxCertainty.Certain)
      } else {
        tb.certain(Constants.MiningRewardsAppId).get == BoxCertainty.Certain &&
          tb.applicationStatuses == Map(Constants.MiningRewardsAppId -> BoxCertainty.Certain)
      }) shouldBe true
    }
  }


  property("scanBlockTransactions") {
    withHybridStore(10) { store =>
      val emptyReg = new WalletRegistry(store)(settings.walletSettings)
      val emptyOff = OffChainRegistry.empty
      val blockId = modIdGen.sample.get

      val height0 = 5
      //simplest case - we're scanning an empty block
      val (r0, o0) = scanBlockTransactions(emptyReg, emptyOff, emptyStateContext, walletVars, height0, blockId, Seq.empty)
      val r0digest = r0.fetchDigest()
      r0digest.walletBalance shouldBe 0
      r0digest.walletAssetBalances.size shouldBe 0
      r0digest.height shouldBe height0

      val o0digest = o0.digest
      o0digest.walletBalance shouldBe 0
      o0digest.walletAssetBalances.size shouldBe 0
      o0digest.height shouldBe height0

      var registry = r0
      var off = o0

      //blocks with one transaction, one creates boxes, another fully spends them
      forAll(trackedTransactionGen){trackedTransaction =>
        val txs = Seq(trackedTransaction.tx)
        val height1 = 5

        val regDigestBefore = registry.fetchDigest().walletBalance
        val offDigestBefore = off.digest.walletBalance

        val (r1, o1) = scanBlockTransactions(registry, off, emptyStateContext, walletVars, height1, blockId, txs)
        val r1digest = r1.fetchDigest()
        r1digest.walletBalance shouldBe (regDigestBefore + trackedTransaction.paymentValues.sum)
        r1digest.walletAssetBalances.size shouldBe 0
        r1digest.height shouldBe height1

        val o1digest = o1.digest
        o1digest.walletBalance shouldBe (offDigestBefore + trackedTransaction.paymentValues.sum)
        o1digest.walletAssetBalances.size shouldBe 0
        o1digest.height shouldBe height1

        registry = r1
        off = o1

        //tx spending outputs of previous transaction but creates identical ones
        val tx = trackedTransaction.tx
        val inputs = tx.outputs.map(_.id).map(id => Input(id, emptyProverResult))
        val spendingTx = ErgoTransaction(inputs, IndexedSeq.empty, tx.outputCandidates)

        val (r2, o2) = scanBlockTransactions(registry, off, emptyStateContext, walletVars, height1 + 1, blockId, Seq(spendingTx))

        val r2digest = r2.fetchDigest()
        r2digest.walletBalance shouldBe (regDigestBefore + trackedTransaction.paymentValues.sum)
        r2digest.walletAssetBalances.size shouldBe 0
        r2digest.height shouldBe height1 + 1

        val o2digest = o2.digest
        o2digest.walletBalance shouldBe (offDigestBefore + trackedTransaction.paymentValues.sum)
        o2digest.walletAssetBalances.size shouldBe 0
        o2digest.height shouldBe height1 + 1

        registry = r2
        off = o2

        val inputs2 = spendingTx.outputs.map(_.id).map(id => Input(id, emptyProverResult))
        val outputs2 = IndexedSeq(new ErgoBoxCandidate(spendingTx.outputs.map(_.value).sum, FalseLeaf.toSigmaProp, height1))
        val spendingTx2 = new ErgoTransaction(inputs2, IndexedSeq.empty, outputs2)

        val (r3, o3) = scanBlockTransactions(registry, off, emptyStateContext, walletVars, height1 + 1, blockId, Seq(spendingTx2))

        val r3digest = r3.fetchDigest()
        r3digest.walletBalance shouldBe regDigestBefore
        r3digest.walletAssetBalances.size shouldBe 0
        r3digest.height shouldBe height1 + 1

        val o3digest = o3.digest
        o3digest.walletBalance shouldBe offDigestBefore
        o3digest.walletAssetBalances.size shouldBe 0
        o3digest.height shouldBe height1 + 1

        registry = r3
        off = o3
      }
    }
  }

}