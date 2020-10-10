package org.ergoplatform.wallet.interpreter

import sigmastate.interpreter.{HintsBag, OwnCommitment}

case class TransactionHintsBag(secretHints: Map[Int, HintsBag], publicHints: Map[Int, HintsBag]) {

  def putHints(index: Int, hintsBag: HintsBag): TransactionHintsBag = {
    val (secret, public) = hintsBag.hints.partition(_.isInstanceOf[OwnCommitment])

    TransactionHintsBag(secretHints.updated(index, HintsBag(secret)), publicHints.updated(index, HintsBag(public)))
  }

  def allHints(index: Int): HintsBag = {
    secretHints.getOrElse(index, HintsBag.empty) ++ publicHints.getOrElse(index, HintsBag.empty)
  }

}

object TransactionHintsBag {

  val empty: TransactionHintsBag = new TransactionHintsBag(Map.empty, Map.empty)

  def apply(mixedHints: Map[Int, HintsBag]): TransactionHintsBag = {
    mixedHints.keys.foldLeft(TransactionHintsBag.empty){ case (thb, idx) =>
      thb.putHints(idx, mixedHints(idx))
    }
  }

}
