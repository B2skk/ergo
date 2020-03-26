package org.ergoplatform.wallet.transactions

import java.util

import scala.collection.IndexedSeq
import scala.language.postfixOps
import org.ergoplatform.ErgoBox
import org.ergoplatform.DataInput
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.ErgoAddress
import org.ergoplatform.ErgoScriptPredef
import org.ergoplatform.UnsignedErgoLikeTransaction
import org.ergoplatform.UnsignedInput
import sigmastate.eval.Extensions._
import scala.util.Try
import scorex.util.{ModifierId, idToBytes, bytesToId}
import org.ergoplatform.wallet.boxes.BoxSelectors
import org.ergoplatform.ErgoBoxAssets
import special.collection.Coll
import sigmastate.eval._
import org.ergoplatform.ErgoBox.TokenId
import scorex.crypto.hash.Digest32
import cats.implicits._

object TransactionBuilder {

  private def calcTokenOutput(outputCandidates: Seq[ErgoBoxCandidate]): Map[ModifierId, Long] = 
    outputCandidates
      .map(b => collTokensToMap(b.additionalTokens))
      .foldLeft(Map[ModifierId, Long]()){case (a, e) => a.combine(e) }

  // TODO: extract into Iso
  private def collTokensToMap(tokens: Coll[(TokenId, Long)]): Map[ModifierId, Long] = 
    tokens.toArray.toSeq.map(t => bytesToId(t._1) -> t._2).toMap

  private def tokensMapToColl(tokens: Map[ModifierId, Long]): Coll[(TokenId, Long)] = 
    tokens.toSeq.map {t => (Digest32 @@ idToBytes(t._1)) -> t._2}.toArray.toColl

  // TODO: scaladoc
  def buildUnsignedTx(
    inputs: IndexedSeq[ErgoBox],
    dataInputs: IndexedSeq[DataInput],
    outputCandidates: Seq[ErgoBoxCandidate],
    feeAmount: Long,
    changeAddress: Option[ErgoAddress],
    currentHeight: Int,
    minFee: Long,
    minChangeValue: Long,
    minerRewardDelay: Int
  ): Try[UnsignedErgoLikeTransaction] = Try {

    // checks from ErgoTransaction.validateStateless
    require(inputs.nonEmpty, "inputs cannot be empty")
    require(outputCandidates.nonEmpty, "outputCandidates cannot be empty")
    require(inputs.size <= Short.MaxValue, s"too many inputs - ${inputs.size} (max ${Short.MaxValue})")
    require(dataInputs.size <= Short.MaxValue, s"too many dataInputs - ${dataInputs.size} (max ${Short.MaxValue})")
    require(outputCandidates.size <= Short.MaxValue, 
      s"too many outputCandidates - ${outputCandidates.size} (max ${Short.MaxValue})")
    require(outputCandidates.forall(_.value >= 0), s"outputCandidate.value must be >= 0")
    val outputSumTry = Try(outputCandidates.map(_.value).reduce(Math.addExact(_, _)))
    require(outputSumTry.isSuccess, s"Sum of transaction output values should not exceed ${Long.MaxValue}")
    require(inputs.distinct.size == inputs.size, s"There should be no duplicate inputs")

    // TODO: implement all appropriate checks from ErgoTransaction.validateStatefull

    require(feeAmount > 0, "Fee amount should be defined")
    val inputTotal  = inputs.map(_.value).sum
    val outputSum   = outputCandidates.map(_.value).sum
    val outputTotal = outputSum + feeAmount
    val changeAmt   = inputTotal - outputTotal
    require(changeAmt >= 0, s"total inputs $inputTotal is less then total outputs $outputTotal")

    val firstInputBoxId = bytesToId(inputs(0).id)
    val tokensOut = calcTokenOutput(outputCandidates)
    // remove minted token if any
    val tokensOutNoMinted = tokensOut.filterKeys(_ != firstInputBoxId)
    val mintedTokensNum = tokensOut.size - tokensOutNoMinted.size
    require(mintedTokensNum <= 1, s"Only one token can be minted, but found $mintedTokensNum")

    val selection = BoxSelectors.select(inputs.toIterator, outputTotal, tokensOutNoMinted).getOrElse(
      throw new IllegalArgumentException(s"failed to calculate change for outputTotal: $outputTotal, \ntokens: $tokensOut, \ninputs: $inputs, ")
    )
    // although we're only interested in change boxes, make sure selection contains exact inputs
    assert(selection.boxes == inputs, s"unexpected selected boxes, expected: $inputs, got ${selection.boxes}")
    val changeBoxes = selection.changeBoxes
    val changeBoxesHaveTokens = changeBoxes.exists(_.tokens.nonEmpty) 

    val noChange = changeAmt < minChangeValue && !changeBoxesHaveTokens

    // if computed changeAmt is too small give it to miner as tips
    val actualFee = if (noChange) feeAmount + changeAmt else feeAmount
    require(
      actualFee >= minFee,
      s"Fee ($actualFee) must be greater then minimum amount ($minFee NanoErg)"
    )
    val feeOut = new ErgoBoxCandidate(
      actualFee,
      ErgoScriptPredef.feeProposition(minerRewardDelay),
      currentHeight
    )

    val addedChangeOut = if (!noChange) {
      require(changeAddress.isDefined, s"change address is required for $changeAmt")
      val script = changeAddress.get.script
      changeBoxes.map { cb =>
        new ErgoBoxCandidate(cb.value, script, currentHeight, tokensMapToColl(cb.tokens))
      }
    } else Seq()

    val finalOutputCandidates = outputCandidates ++ Seq(feeOut) ++ addedChangeOut

    new UnsignedErgoLikeTransaction(
      inputs.map(b => new UnsignedInput(b.id)),
      dataInputs,
      finalOutputCandidates.toIndexedSeq
    )
  }
}
