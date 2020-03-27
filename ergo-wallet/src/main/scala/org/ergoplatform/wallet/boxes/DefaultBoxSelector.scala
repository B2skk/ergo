package org.ergoplatform.wallet.boxes

import scorex.util.ModifierId

/**
  * Default implementation of the box selector. It simply picks boxes till sum of their monetary values
  * meets target Ergo balance, then it checks which assets are not fulfilled and adds boxes till target
  * asset values are met.
  */
object DefaultBoxSelector extends BoxSelector {

  import BoxSelector._

  override def select(inputBoxes: Iterator[TrackedBox],
                      externalFilter: TrackedBox => Boolean,
                      targetBalance: Long,
                      targetAssets: Map[ModifierId, Long]): Option[BoxSelectionResult] = {
    BoxSelectors.select(inputBoxes, externalFilter, targetBalance, targetAssets)
      .map { res =>
        BoxSelectionResult(res.boxes, res.changeBoxes)
       }
  }

}
