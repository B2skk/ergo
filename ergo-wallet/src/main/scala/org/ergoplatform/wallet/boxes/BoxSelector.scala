package org.ergoplatform.wallet.boxes

import org.ergoplatform.ErgoBoxAssets
import org.ergoplatform.ErgoBox.MaxTokens
import org.ergoplatform.wallet.boxes.BoxSelector.BoxSelectionResult
import scorex.util.ModifierId
import scala.collection.mutable


/**
  * An interface which is exposing a method to select unspent boxes according to target amounts in Ergo tokens and
  * assets and possible user-defined filter. The interface could have many instantiations implementing
  * different strategies.
  */
trait BoxSelector {

  /**
    * A method which is selecting boxes to spend in order to collect needed amounts of ergo tokens and assets.
    *
    * @param inputBoxes    - unspent boxes to choose from.
    * @param filterFn      - user-provided filter function for boxes. From inputBoxes, only ones to be chosen for which
    *                      filterFn(box) returns true
    * @param targetBalance - ergo balance to be met
    * @param targetAssets  - assets balances to be met
    * @return None if select() is failing to pick appropriate boxes, otherwise Some(res), where res contains boxes
    *         to spend as well as monetary values and assets for boxes containing change
    *         (wrapped in a special BoxSelectionResult class).
    */
  def select[T <: ErgoBoxAssets](inputBoxes: Iterator[T],
             filterFn: T => Boolean,
             targetBalance: Long,
             targetAssets: Map[ModifierId, Long]): Option[BoxSelectionResult[T]]
  
  def select[T <: ErgoBoxAssets](inputBoxes: Iterator[T],
    targetBalance: Long,
    targetAssets: Map[ModifierId, Long]
  ): Option[BoxSelectionResult[T]] =
    select(inputBoxes, _ => true, targetBalance, targetAssets)


}

object BoxSelector {

  final case class BoxSelectionResult[T <: ErgoBoxAssets](boxes: Seq[T], changeBoxes: Seq[ErgoBoxAssets])

  def mergeAssetsMut(
    into: mutable.Map[ModifierId, Long],
    from: Map[ModifierId, Long]*
  ): Unit = {
    from.foreach(_.foreach {
      case (id, amount) =>
        into.put(id, into.getOrElse(id, 0L) + amount)
    })
  }

  def subtractAssetsMut(
    from: mutable.Map[ModifierId, Long],
    subtractor: Map[ModifierId, Long]
  ): Unit = {
    subtractor.foreach {
      case (id, subtractAmt) =>
        val fromAmt = from(id)
        if (fromAmt == subtractAmt) {
          from.remove(id)
        } else {
          from.put(id, fromAmt - subtractAmt)
        }
    }
  }

}
