package org.ergoplatform.nodeView.history.components

import org.ergoplatform.settings.ErgoSettings
import scorex.core.utils.NetworkTimeProvider

trait Configuration {

  protected val timeProvider: NetworkTimeProvider

  protected val settings: ErgoSettings

}
