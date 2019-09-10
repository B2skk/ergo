package org.ergoplatform.nodeView.history.components

import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.modifiers.state.UTXOSnapshotChunk
import org.ergoplatform.nodeView.history.storage.LDBHistoryStorage
import scorex.core.consensus.History.ProgressInfo
import scorex.core.utils.ScorexEncoding
import scorex.util.ScorexLogging

import scala.util.{Failure, Success, Try}

/**
  * Contains all functions required by History to process UTXOSnapshotChunk
  */
trait UTXOSnapshotChunkProcessor {
  self: Persistence with ScorexLogging with ScorexEncoding =>

  def process(m: UTXOSnapshotChunk): ProgressInfo[ErgoPersistentModifier] = {
    //TODO
    val toInsert = ???
    storage.update(Seq.empty, toInsert)
    ProgressInfo(None, Seq.empty, Seq(m), Seq.empty)
  }

  def validate(m: UTXOSnapshotChunk): Try[Unit] = if (storage.contains(m.id)) {
    Failure(new Error(s"UTXOSnapshotChunk with id ${m.encodedId} is already in history"))
  } else {
    Success(Unit)
  }

}
