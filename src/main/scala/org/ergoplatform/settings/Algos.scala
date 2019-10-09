package org.ergoplatform.settings

import io.iohk.iodb.ByteArrayWrapper
import scorex.core.{VersionTag, versionToBytes}
import scorex.crypto.authds.LeafData
import scorex.crypto.authds.merkle.MerkleTree
import scorex.crypto.hash.Digest32
import scorex.util._

object Algos extends ErgoAlgos with scorex.core.utils.ScorexEncoding {

  // due to ErgoAlgos from sigma extends scorex.util.ScorexEncoding where encoder is BytesEncoder
  override implicit val encoder: scorex.core.utils.ScorexEncoder = scorex.core.utils.ScorexEncoder.default

  lazy val emptyMerkleTreeRoot: Digest32 = Algos.hash(LeafData @@ Array[Byte]())

  @inline def versionToBAW(id: VersionTag): ByteArrayWrapper = ByteArrayWrapper(versionToBytes(id))

  @inline def idToBAW(id: ModifierId): ByteArrayWrapper = ByteArrayWrapper(idToBytes(id))

  @inline def encode(id: ModifierId): String = encoder.encode(id)

  def blockIdDifficulty(id: Array[Byte]): BigInt = {
    val blockTarget = BigInt(1, id).ensuring(_ <= Constants.MaxTarget)
    Constants.MaxTarget / blockTarget
  }

  def merkleTreeRoot(elements: Seq[LeafData]): Digest32 =
    if (elements.isEmpty) emptyMerkleTreeRoot else MerkleTree(elements)(hash).rootHash
}
