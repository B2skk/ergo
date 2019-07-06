package org.ergoplatform.db

import akka.util.ByteString
import io.iohk.iodb.Store.{K, V}
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import org.ergoplatform.db.LDBFactory.factory
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.settings.Algos
import org.ergoplatform.utils.generators.ErgoTransactionGenerators
import org.iq80.leveldb.Options
import org.scalameter.KeyValue
import org.scalameter.api.{Bench, Gen, _}
import org.scalameter.picklers.Implicits._
import scorex.testkit.utils.FileUtils
import scorex.util.idToBytes

import scala.util.Random

object LDBStoreBenchmark
  extends Bench.ForkedTime
    with ErgoTransactionGenerators
    with FileUtils {

  private val options = new Options()
  options.createIfMissing(true)
  private val db = factory.open(createTempDir, options)

  private val storeLDB = new VersionedLDBKVStore(db)
  private val storeLSM = new LSMStore(createTempDir)

  private val modsNumGen = Gen.enumeration("modifiers number")(100, 400)

  val txsGen: Gen[Seq[BlockTransactions]] = modsNumGen.map { num =>
    (0 to num).flatMap { _ =>
      invalidBlockTransactionsGen.sample
    }
  }

  private val config = Seq[KeyValue](
    exec.minWarmupRuns -> 5,
    exec.maxWarmupRuns -> 10,
    exec.benchRuns -> 10,
    exec.requireGC -> true
  )

  private def benchWriteLDB(bts: Seq[BlockTransactions]): Unit = {
    val toInsert = bts.map(bt => ByteString(idToBytes(bt.headerId)) -> ByteString(bt.bytes))
    storeLDB.insert(toInsert)(ByteString(Algos.hash(idToBytes(bts.head.headerId))))
  }

  private def benchWriteReadLDB(bts: Seq[BlockTransactions]): Unit = {
    val toInsert = bts.map(bt => ByteString(idToBytes(bt.headerId)) -> ByteString(bt.bytes))
    storeLDB.insert(toInsert)(ByteString(Algos.hash(idToBytes(bts.head.headerId))))
    bts.foreach { bt => storeLDB.get(ByteString(idToBytes(bt.headerId))) }
  }

  private def benchGetAllLDB: Seq[(ByteString, ByteString)] = storeLDB.getAll

  private def benchWriteLSM(bts: Seq[BlockTransactions]): Unit = {
    val toInsert = bts.map(bt => ByteArrayWrapper(idToBytes(bt.headerId)) -> ByteArrayWrapper(bt.bytes))
    storeLSM.update(Random.nextLong(), Seq.empty, toInsert)
  }

  private def benchWriteReadLSM(bts: Seq[BlockTransactions]): Unit = {
    val toInsert = bts.map(bt => ByteArrayWrapper(idToBytes(bt.headerId)) -> ByteArrayWrapper(bt.bytes))
    storeLSM.update(Random.nextLong(), Seq.empty, toInsert)
    bts.foreach { bt => storeLSM.get(ByteArrayWrapper(idToBytes(bt.headerId))) }
  }

  private def benchGetAllLSM: Iterator[(K, V)] = storeLSM.getAll

  performance of "LDBStore vs LSMStore" in {
    performance of "LDBStore write" in {
      using(txsGen) config (config: _*) in (bts => benchWriteLDB(bts))
    }
    performance of "LDBStore write/read" in {
      using(txsGen) config (config: _*) in (bts => benchWriteReadLDB(bts))
    }
    performance of "LDBStore getAll" in {
      using(txsGen) config (config: _*) in (_ => benchGetAllLDB)
    }
    performance of "LSMStore write" in {
      using(txsGen) config (config: _*) in (bts => benchWriteLSM(bts))
    }
    performance of "LSMStore write/read" in {
      using(txsGen) config (config: _*) in (bts => benchWriteReadLSM(bts))
    }
    performance of "LSMStore getAll" in {
      using(txsGen) config (config: _*) in (_ => benchGetAllLSM)
    }
  }

}
