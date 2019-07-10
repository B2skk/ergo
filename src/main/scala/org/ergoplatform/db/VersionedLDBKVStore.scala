package org.ergoplatform.db

import akka.util.ByteString
import org.ergoplatform.settings.{Algos, Constants}
import org.iq80.leveldb.{DB, ReadOptions}

import scala.util.{Failure, Success, Try}

/**
  * A LevelDB wrapper providing additional versioning layer along with a convenient db interface.
  */
final class VersionedLDBKVStore(protected val db: DB, keepVersions: Int) extends KVStore {

  import VersionedLDBKVStore.VersionId

  val VersionsKey: Array[Byte] = Algos.hash("versions")

  val ChangeSetPrefix: Byte = 0x16

  /**
    * Performs versioned update.
    * @param toInsert - key, value pairs to be inserted/updated
    * @param toRemove - keys to be removed
    */
  def update(toInsert: Seq[(K, V)], toRemove: Seq[K])(version: VersionId): Unit = {
    require(version.length == Constants.HashLength, "Illegal version id size")
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    require(Option(db.get(version, ro)).isEmpty, "Version id is already used")
    val (insertedKeys, altered) = toInsert.foldLeft(Seq.empty[K], Seq.empty[(K, V)]) {
      case ((insertedAcc, alteredAcc), (k, _)) =>
        Option(db.get(k, ro))
          .map { oldValue =>
            insertedAcc -> ((k -> oldValue) +: alteredAcc)
          }
          .getOrElse {
            (k +: insertedAcc) -> alteredAcc
          }
    }
    val removed = toRemove.flatMap { k =>
      Option(db.get(k, ro)).map(k -> _)
    }
    val changeSet = ChangeSet(insertedKeys, removed, altered)
    val (updatedVersions, versionsToShrink) = Option(db.get(VersionsKey, ro))
      .map(version ++ _) // newer version first
      .getOrElse(version)
      .splitAt(Constants.HashLength * keepVersions) // shrink old versions
    val versionIdsToShrink = versionsToShrink.grouped(Constants.HashLength)
    val batch = db.createWriteBatch()
    try {
      batch.put(VersionsKey, updatedVersions)
      versionIdsToShrink.foreach(batch.delete)
      batch.put(version, ChangeSetPrefix +: ChangeSetSerializer.toBytes(changeSet))
      toInsert.foreach { case (k, v) => batch.put(k, v) }
      toRemove.foreach(x => batch.delete(x))
      db.write(batch)
    } finally {
      batch.close()
      ro.snapshot().close()
    }
  }

  def insert(toInsert: Seq[(K, V)])(version: VersionId): Unit = update(toInsert, Seq.empty)(version)

  def remove(toRemove: Seq[K])(version: VersionId): Unit = update(Seq.empty, toRemove)(version)

  /**
    * Rolls storage state back to the specified checkpoint.
    * @param versionId - version id to roll back to
    */
  def rollbackTo(versionId: VersionId): Try[Unit] = {
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    Option(db.get(VersionsKey)) match {
      case Some(bytes) =>
        val batch = db.createWriteBatch()
        try {
          val versionsToRollBack = bytes
            .grouped(Constants.HashLength)
            .takeWhile(ByteString(_) != ByteString(versionId))

          versionsToRollBack
            .foldLeft(Seq.empty[(Array[Byte], ChangeSet)]) { case (acc, verId) =>
              val changeSetOpt = Option(db.get(verId, ro)).flatMap { changeSetBytes =>
                ChangeSetSerializer.parseBytesTry(changeSetBytes.tail).toOption
              }
              require(changeSetOpt.isDefined, s"Inconsistent versioned storage state")
              acc ++ changeSetOpt.toSeq.map(verId -> _)
            }
            .foreach { case (verId, changeSet) => // revert all changes (from newest version to the targeted one)
              changeSet.insertedKeys.foreach(k => batch.delete(k))
              changeSet.removed.foreach { case (k, v) =>
                batch.put(k, v)
              }
              changeSet.altered.foreach { case (k, oldV) =>
                batch.put(k, oldV)
              }
              batch.delete(verId)
            }

          val updatedVersions = bytes
            .grouped(Constants.HashLength)
            .map(ByteString.apply)
            .dropWhile(_ != ByteString(versionId))
            .reduce(_ ++ _)

          versionsToRollBack.foreach(batch.delete) // eliminate rolled back versions
          batch.put(VersionsKey, updatedVersions.toArray)

          db.write(batch)
          Success(())
        } finally {
          batch.close()
          ro.snapshot().close()
        }
      case None =>
        Failure(new Exception(s"Version ${Algos.encode(versionId)} not found"))
    }
  }

  def versions: Seq[VersionId] = Option(db.get(VersionsKey))
    .toSeq
    .flatMap(_.grouped(Constants.HashLength))

  def versionIdExists(versionId: VersionId): Boolean =
    versions.exists(v => ByteString(v) == ByteString(versionId))

}

object VersionedLDBKVStore {
  type VersionId = Array[Byte]
}
