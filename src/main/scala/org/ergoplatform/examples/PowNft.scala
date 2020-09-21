package org.ergoplatform.examples

import org.ergoplatform.ErgoBox.{R4, R5}
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox}
import scorex.util.encode.Base16
import sigmastate.Values.{ByteArrayConstant, LongConstant}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.modifiers.mempool.{UnsignedInput, UnsignedErgoTransaction}

object PowNft extends App {

  val enc = new ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
  val pk = enc.fromString("9iHWcYYSPkgYbnC6aHfZcLZrKrrkpFzM2ETUZ2ikFqFwVAB2CU7").get.script

  val preimage = "kushti2020".getBytes("UTF-8")

  val inputBox: ErgoBox = ???
  val input: UnsignedInput = new UnsignedInput(inputBox.id)


  // todo: generate transaction with the box
  def nftGeneratorCandidate(nonce:Long): UnsignedErgoTransaction = {
    val candidate = new ErgoBoxCandidate(1000000L, pk, 300000, Colls.emptyColl, Map(R4 -> ByteArrayConstant(preimage), R5 -> LongConstant(nonce)))
    val tx = new ErgoTransaction(IndexedSeq(input), IndexedSeq.empty, IndexedSeq(candidate))
  }

  def mineNftGeneratorBox(target: BigInt): (ErgoBox, Long) = {
    (0 to Int.MaxValue).foreach{nonce =>
      val b = nftGeneratorCandidate(nonce).outputs.head
      val id = b.id
      println("nonce: " + nonce)
      val numId = BigInt(1, id)
      println(numId)
      if(numId < target) return b -> nonce
    }
    ???
  }


  val target = BigInt(2).pow(224)
  val (b, nonce) = mineNftGeneratorBox(target)

  println("Token stamp: " + new String(preimage, "UTF-8"))
  println("Worker stamp: " + nonce)
  println("Minted token id: " + Base16.encode(b.id))
}
