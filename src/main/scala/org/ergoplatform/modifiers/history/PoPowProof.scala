package org.ergoplatform.modifiers.history

import org.ergoplatform.modifiers.ErgoPersistentModifier
import scorex.core.ModifierTypeId
import scorex.core.serialization.ScorexSerializer
import scorex.util.ModifierId
import scorex.util.serialization.{Reader, Writer}

final case class PoPowProof(prefix: PoPowProofPrefix, suffix: PoPowProofSuffix)
  extends ErgoPersistentModifier {

  override type M = PoPowProof

  override val modifierTypeId: ModifierTypeId = PoPowProof.TypeId

  override val sizeOpt: Option[Int] = None

  override def serializedId: Array[Byte] = prefix.serializedId

  override def serializer: ScorexSerializer[M] = NiPoPowProofSerializer

  override def parentId: ModifierId = prefix.parentId

  def chain: Seq[Header] = prefix.chain ++ suffix.chain

  //  def validate: Try[Unit] = prefix.validate.flatMap(_ => suffix.validate)

}

object PoPowProof {

  val TypeId: ModifierTypeId = ModifierTypeId @@ (110: Byte)

  def apply(m: Int, k: Int, prefixChain: Seq[Header], suffixChain: Seq[Header]): PoPowProof = {
    val suffix = PoPowProofSuffix(k, suffixChain)
    val prefix = PoPowProofPrefix(m, prefixChain, suffix.id)
    new PoPowProof(prefix, suffix)
  }

}

object NiPoPowProofSerializer extends ScorexSerializer[PoPowProof] {

  override def serialize(obj: PoPowProof, w: Writer): Unit = {
    val prefixBytes = obj.prefix.bytes
    val suffixBytes = obj.suffix.bytes
    w.putInt(prefixBytes.length)
    w.putBytes(prefixBytes)
    w.putInt(suffixBytes.length)
    w.putBytes(suffixBytes)
  }

  override def parse(r: Reader): PoPowProof = {
    val prefixSize = r.getInt()
    val prefix = NiPoPowProofPrefixSerializer.parseBytes(r.getBytes(prefixSize))
    val suffixSize = r.getInt()
    val suffix = NiPoPowProofSuffixSerializer.parseBytes(r.getBytes(suffixSize))
    PoPowProof(prefix, suffix)
  }

}
