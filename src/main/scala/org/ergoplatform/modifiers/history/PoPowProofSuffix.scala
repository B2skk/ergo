package org.ergoplatform.modifiers.history

import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.settings.Algos
import scorex.core.ModifierTypeId
import scorex.core.serialization.ScorexSerializer
import scorex.util.ModifierId
import scorex.util.serialization.{Reader, Writer}

/**
  * A structure representing NiPoPow proof suffix as a persistent modifier.
  * @param k        - security parameter (min suffix length)
  * @param chain    - proof suffix headers
  * @param sizeOpt  - size of the modifier
  */
final case class PoPowProofSuffix(k: Int,
                                  chain: Seq[PoPowHeader],
                                  sizeOpt: Option[Int] = None)
  extends ErgoPersistentModifier {

  override type M = PoPowProofSuffix

  override val modifierTypeId: ModifierTypeId = PoPowProof.TypeId

  override def serializedId: Array[Byte] = Algos.hash(bytes)

  override def serializer: ScorexSerializer[M] = PoPowProofSuffixSerializer

  override def parentId: ModifierId = chain.head.id

  def headersChain: Seq[Header] = chain.map(_.header)

}

object PoPowProofSuffix {
  val TypeId: ModifierTypeId = ModifierTypeId @@ (112: Byte)
}

object PoPowProofSuffixSerializer extends ScorexSerializer[PoPowProofSuffix] {

  override def serialize(obj: PoPowProofSuffix, w: Writer): Unit = {
    w.putInt(obj.k)
    w.putInt(obj.chain.size)
    obj.chain.foreach { h =>
      val hBytes = h.bytes
      w.putInt(hBytes.length)
      w.putBytes(hBytes)
    }
  }

  override def parse(r: Reader): PoPowProofSuffix = {
    val startPos = r.position
    val k = r.getInt()
    val suffixSize = r.getInt()
    val suffix = (0 until suffixSize).map { _ =>
      val size = r.getInt()
      PoPowHeaderSerializer.parseBytes(r.getBytes(size))
    }
    PoPowProofSuffix(k, suffix, Some(r.position - startPos))
  }

}
