package org.ergoplatform.nodeView.history

import java.io.File

import org.ergoplatform.db.LDBFactory.factory
import org.ergoplatform.db.LDBKVStore
import org.ergoplatform.mining.AutolykosPowScheme
import org.ergoplatform.modifiers.history._
import org.ergoplatform.modifiers.state.UTXOSnapshotChunk
import org.ergoplatform.modifiers.{BlockSection, ErgoFullBlock, ErgoPersistentModifier}
import org.ergoplatform.nodeView.history.storage.LDBHistoryStorage
import org.ergoplatform.nodeView.history.components._
import org.ergoplatform.nodeView.history.components.popow.{EmptyPoPowProcessor, ProvingPoPowProcessor}
import org.ergoplatform.settings._
import org.ergoplatform.utils.LoggingUtil
import org.ergoplatform.nodeView.history.storage.StorageKeys._
import org.iq80.leveldb.Options
import scorex.core.consensus.History
import scorex.core.consensus.History.ProgressInfo
import scorex.core.utils.{NetworkTimeProvider, ScorexEncoder}
import scorex.core.validation.RecoverableModifierError
import scorex.util.{ScorexLogging, idToBytes}

import scala.util.{Failure, Try}

/**
  * History implementation. It is processing persistent modifiers generated locally or coming from the network.
  * Depending on chosen node settings, it will process modifiers in a different way, different processors define how to
  * process different type of modifiers.
  *
  * HeadersProcessor: processor of block headers. It's the same for all node settings
  * ADProofsProcessor: processor of ADProofs. ADProofs may
  *   1. Be downloaded from other nodes (ADState == true)
  *   2. Be calculated by using local state (ADState == false)
  *   3. Be ignored by history in light mode (verifyTransactions == false)
  * PoPoWProofsProcessor: processor of PoPoWProof. PoPoWProof may
  *   1. Be downloaded once during bootstrap from other peers (poPoWBootstrap == true)
  *   2. Be ignored by history (poPoWBootstrap == false)
  * BlockTransactionsProcessor: Processor of BlockTransactions. BlockTransactions may
  *   1. Be downloaded from other peers (verifyTransactions == true)
  *   2. Be ignored by history (verifyTransactions == false)
  */
trait ErgoHistory
  extends History[ErgoPersistentModifier, ErgoSyncInfo, ErgoHistory]
    with ErgoHistoryReader {

  override type NVCT = ErgoHistory

  def closeStorage(): Unit = storage.close()

  /**
    * Append ErgoPersistentModifier to History if valid
    */
  override def append(modifier: ErgoPersistentModifier): Try[(ErgoHistory, ProgressInfo[ErgoPersistentModifier])] = {
    log.debug(s"Trying to append modifier ${modifier.encodedId} of type ${modifier.modifierTypeId} to history")
    applicableTry(modifier).map { _ =>
      modifier match {
        case header: Header =>
          (this, process(header))
        case section: BlockSection =>
          (this, process(section))
        case poPowProof: PoPowProof =>
          (this, process(poPowProof))
        case chunk: UTXOSnapshotChunk =>
          (this, process(chunk))
      }
    }.recoverWith { case e =>
      if (!e.isInstanceOf[RecoverableModifierError]) {
        log.warn(s"Error while applying modifier ${modifier.encodedId} of type ${modifier.modifierTypeId}, " +
          s"reason: ${LoggingUtil.getReasonMsg(e)} ")
      }
      Failure(e)
    }
  }

  /**
    * Mark modifier as valid
    */
  override def reportModifierIsValid(modifier: ErgoPersistentModifier): ErgoHistory = {
    log.debug(s"Modifier ${modifier.encodedId} of type ${modifier.modifierTypeId} is marked as valid ")
    modifier match {
      case fb: ErgoFullBlock =>
        val nonMarkedIds = (fb.header.id +: fb.header.sectionIds.map(_._2))
          .filter(id => storage.getIndex(validityKey(id)).isEmpty)

        if (nonMarkedIds.nonEmpty) {
          storage.update(
            nonMarkedIds.map(id => validityKey(id) -> Array(1.toByte)),
            Seq.empty)
        }
      case _ =>
        storage.update(
          Seq(validityKey(modifier.id) -> Array(1.toByte)),
          Seq.empty)
    }
    this
  }

  /**
    * Mark modifier and all modifiers in child chains as invalid
    *
    * @param modifier that is invalid from State point of view
    * @return ProgressInfo with next modifier to try to apply
    */
  @SuppressWarnings(Array("OptionGet", "TraversableHead"))
  override def reportModifierIsInvalid(modifier: ErgoPersistentModifier,
                                       progressInfo: ProgressInfo[ErgoPersistentModifier]
                                      ): (ErgoHistory, ProgressInfo[ErgoPersistentModifier]) = {
    log.debug(s"Modifier ${modifier.encodedId} of type ${modifier.modifierTypeId} is marked as invalid")
    correspondingHeader(modifier) match {
      case Some(invalidatedHeader) =>
        val invalidatedHeaders = continuationHeaderChains(invalidatedHeader, _ => true).flatten.distinct
        val invalidatedIds = invalidatedHeaders.map(_.id).toSet
        val validityRow = invalidatedHeaders.flatMap(h => Seq(h.id, h.transactionsId, h.ADProofsId)
          .map(id => validityKey(id) -> Array(0.toByte)))
        log.info(s"Going to invalidate ${invalidatedHeader.encodedId} and ${invalidatedHeaders.map(_.encodedId)}")
        val bestHeaderIsInvalidated = bestHeaderIdOpt.exists(id => invalidatedIds.contains(id))
        val bestFullIsInvalidated = bestFullBlockIdOpt.exists(id => invalidatedIds.contains(id))
        (bestHeaderIsInvalidated, bestFullIsInvalidated) match {
          case (false, false) =>
            // Modifiers from best header and best full chain are not involved, no rollback and links change required
            storage.update(validityRow, Seq.empty)
            this -> ErgoHistory.emptyProgressInfo
          case _ =>
            // Modifiers from best header and best full chain are involved, links change required
            val newBestHeaderOpt = loopHeightDown(bestHeaderHeight, id => !invalidatedIds.contains(id))

            if (!bestFullIsInvalidated) {
              // Only headers chain involved
              storage.update(
                newBestHeaderOpt.map(h => BestHeaderKey -> idToBytes(h.id)).toSeq,
                Seq.empty
              )
              this -> ErgoHistory.emptyProgressInfo
            } else {
              val invalidatedChain: Seq[ErgoFullBlock] = bestFullBlockOpt.toSeq
                .flatMap(f => headerChainBack(bestFullBlockHeight + 1, f.header, h => !invalidatedIds.contains(h.id)))
                .flatMap(getFullBlock)
                .ensuring(_.lengthCompare(1) >= 0, "invalidatedChain should contain at least bestFullBlock")

              val genesisInvalidated = invalidatedChain.lengthCompare(1) == 0
              val branchPointHeader = if (genesisInvalidated) PreGenesisHeader else invalidatedChain.head.header

              val validHeadersChain =
                continuationHeaderChains(branchPointHeader,
                  h => getFullBlock(h).isDefined && !invalidatedIds.contains(h.id))
                  .maxBy(_.lastOption.flatMap(x => scoreOf(x.id)).getOrElse(BigInt(0)))

              val validChain = validHeadersChain.tail.flatMap(getFullBlock)

              val chainStatusRow = validChain.map(b =>
                FullBlockProcessor.chainStatusKey(b.id) -> FullBlockProcessor.BestChainMarker) ++
                invalidatedHeaders.map(h =>
                  FullBlockProcessor.chainStatusKey(h.id) -> FullBlockProcessor.NonBestChainMarker)

              val changedLinks = validHeadersChain.lastOption.map(b => BestFullBlockKey -> idToBytes(b.id)) ++
                newBestHeaderOpt.map(h => BestHeaderKey -> idToBytes(h.id)).toSeq
              val toInsert = validityRow ++ changedLinks ++ chainStatusRow
              storage.update(toInsert, Seq.empty)
              val toRemove = if (genesisInvalidated) invalidatedChain else invalidatedChain.tail

              this -> ProgressInfo(Some(branchPointHeader.id), toRemove, validChain, Seq.empty)
            }
        }
      case None =>
        //No headers become invalid. Just valid this modifier as invalid
        storage.update(
          Seq(validityKey(modifier.id) -> Array(0.toByte)),
          Seq.empty)
        this -> ErgoHistory.emptyProgressInfo
    }
  }

  /**
    * @return header, that corresponds to modifier
    */
  protected def correspondingHeader(modifier: ErgoPersistentModifier): Option[Header] = modifier match {
    case h: Header => Some(h)
    case full: ErgoFullBlock => Some(full.header)
    case proof: ADProofs => typedModifierById[Header](proof.headerId)
    case txs: BlockTransactions => typedModifierById[Header](txs.headerId)
    case _ => None
  }

}

object ErgoHistory extends ScorexLogging {

  type Height = Int
  type Score = BigInt
  type Difficulty = BigInt
  type NBits = Long

  val CharsetName = "UTF-8"

  val EmptyHistoryHeight: Int = 0
  val GenesisHeight: Int = EmptyHistoryHeight + 1

  def emptyProgressInfo(implicit e: ScorexEncoder): ProgressInfo[ErgoPersistentModifier] =
    ProgressInfo[ErgoPersistentModifier](None, Seq.empty, Seq.empty, Seq.empty)

  def heightOf(headerOpt: Option[Header]): Int = headerOpt.map(_.height).getOrElse(EmptyHistoryHeight)

  def historyDir(settings: ErgoSettings): File = {
    val dir = new File(s"${settings.directory}/history")
    dir.mkdirs()
    dir
  }

  def createDb(path: String): LDBKVStore = {
    val dir = new File(path)
    dir.mkdirs()
    val options = new Options()
    options.createIfMissing(true)
    val db = factory.open(dir, options)
    new LDBKVStore(db)
  }

  def readOrGenerate(ergoSettings: ErgoSettings, ntp: NetworkTimeProvider): ErgoHistory = {
    val indexStore = createDb(s"${ergoSettings.directory}/history/index")
    val objectsStore = createDb(s"${ergoSettings.directory}/history/objects")
    val db = new LDBHistoryStorage(indexStore, objectsStore, ergoSettings.cacheSettings)
    val nodeConfiguration = ergoSettings.nodeSettings

    val history: ErgoHistory =
      nodeConfiguration.historyMode match {
        case HistoryOperationMode.Full =>
          new ErgoHistory with FullBlockSectionProcessor with FullBlockProcessor
            with EmptyPoPowProcessor with Logging.Live {
            override protected val settings: ErgoSettings = ergoSettings
            override protected[history] val storage: LDBHistoryStorage = db
            override val powScheme: AutolykosPowScheme = chainSettings.powScheme
            override protected val timeProvider: NetworkTimeProvider = ntp
          }
        case HistoryOperationMode.FullProving =>
          new ErgoHistory with FullBlockSectionProcessor with FullBlockProcessor
            with ProvingPoPowProcessor with Logging.Live {
            override protected val settings: ErgoSettings = ergoSettings
            override protected[history] val storage: LDBHistoryStorage = db
            override val powScheme: AutolykosPowScheme = chainSettings.powScheme
            override protected val timeProvider: NetworkTimeProvider = ntp
          }
        case HistoryOperationMode.Light =>
          new ErgoHistory with EmptyBlockSectionProcessor
            with EmptyPoPowProcessor with Logging.Live {
            override protected val settings: ErgoSettings = ergoSettings
            override protected[history] val storage: LDBHistoryStorage = db
            override val powScheme: AutolykosPowScheme = chainSettings.powScheme
            override protected val timeProvider: NetworkTimeProvider = ntp
          }
      }
      history
  }

}
