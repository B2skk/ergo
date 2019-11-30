package org.ergoplatform.nodeView.history.components.popow

import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.modifiers.history.PoPowProof
import org.ergoplatform.nodeView.history.components.HeadersProcessor
import org.ergoplatform.settings.PoPowParams
import scorex.core.consensus.History.ProgressInfo
import scorex.core.utils.ScorexEncoding

import scala.util.{Failure, Try}

trait EmptyPoPowProcessor extends PoPowProcessor {
  self: HeadersProcessor with ScorexEncoding =>

  final def validate(m: PoPowProof): Try[Unit] =
    Failure(new Error("PoPowProof validation is not supported"))

  final def process(m: PoPowProof): ProgressInfo[ErgoPersistentModifier] =
    ProgressInfo(None, Seq.empty, Seq.empty, Seq.empty)

  def prove(params: PoPowParams): Try[PoPowProof] =
    Failure(new Exception("PoPow proving is not supported"))

}

