package org.ergoplatform.nodeView.history.storage

import org.ergoplatform.modifiers.ErgoPersistentModifier
import scorex.util.ModifierId

import scala.util.Try

trait ObjectsStore {

  def get(id: ModifierId): Option[Array[Byte]]

  def delete(id: ModifierId): Try[Unit]

  def put(m: ErgoPersistentModifier): Try[Unit]

  def contains(id: ModifierId): Boolean

}
