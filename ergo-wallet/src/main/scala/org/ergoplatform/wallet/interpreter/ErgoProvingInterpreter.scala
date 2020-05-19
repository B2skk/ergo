package org.ergoplatform.wallet.interpreter

import java.math.BigInteger
import java.util

import org.ergoplatform._
import org.ergoplatform.validation.ValidationRules
import org.ergoplatform.wallet.protocol.context.{ErgoLikeParameters, ErgoLikeStateContext, TransactionContext}
import org.ergoplatform.wallet.secrets.{ExtendedSecretKey, SecretKey}
import sigmastate.Values.SigmaBoolean
import sigmastate.basics.DLogProtocol.{DLogInteractiveProver, ProveDlog}
import sigmastate.basics.{DiffieHellmanTupleInteractiveProver, FirstProverMessage, ProveDHTuple, SigmaProtocolPrivateInput}
import sigmastate.eval.{CompiletimeIRContext, IRContext}
import sigmastate.interpreter.{HintsBag, ProverInterpreter}

import scala.util.{Failure, Success, Try}

/**
  * A class which is holding secrets and signing transactions.
  * Signing a transaction means spending proofs generation for all of its input boxes.
  *
  * @param secretKeys - secrets used by the prover
  * @param params     - ergo network parameters at the moment of proving
  */
class ErgoProvingInterpreter(val secretKeys: IndexedSeq[SecretKey], params: ErgoLikeParameters)
                            (implicit IR: IRContext)
  extends ErgoInterpreter(params) with ProverInterpreter {

  /**
    * Interpreter's secrets, in form of sigma protocols private inputs
    */
  val secrets: IndexedSeq[SigmaProtocolPrivateInput[_, _]] = secretKeys.map(_.privateInput)

  /**
    * Only secrets corresponding to hierarchical deterministic scheme (BIP-32 impl)
    */
  val hdKeys: IndexedSeq[ExtendedSecretKey] = secretKeys.collect { case ek: ExtendedSecretKey => ek }

  /**
    * Only public keys corresponding to hierarchical deterministic scheme (BIP-32 impl)
    */
  val hdPubKeys: IndexedSeq[ProveDlog] = hdKeys.map(_.publicImage)

  /**
    * Create new prover instance with additional hints added
    * @param additionalHints - hints to add to the prover
    * @return updated prover
    */
  def addHints(additionalHints: HintsBag): ErgoProvingInterpreter =
    new ErgoProvingInterpreter(secretKeys, params, hintsBag ++ additionalHints)

  /**
    * @note requires `unsignedTx` and `boxesToSpend` have the same boxIds in the same order.
    */
  def sign(unsignedTx: UnsignedErgoLikeTransaction,
           boxesToSpend: IndexedSeq[ErgoBox],
           dataBoxes: IndexedSeq[ErgoBox],
           stateContext: ErgoLikeStateContext): Try[ErgoLikeTransaction] = {
    if (unsignedTx.inputs.length != boxesToSpend.length) Failure(new Exception("Not enough boxes to spend"))
    else if (unsignedTx.dataInputs.length != dataBoxes.length) Failure(new Exception("Not enough data boxes"))
    else boxesToSpend
      .zipWithIndex
      .foldLeft(Try(IndexedSeq[Input]() -> 0L)) { case (inputsCostTry, (inputBox, boxIdx)) =>
        val unsignedInput = unsignedTx.inputs(boxIdx)
        require(util.Arrays.equals(unsignedInput.boxId, inputBox.id))

        val transactionContext = TransactionContext(boxesToSpend, dataBoxes, unsignedTx, boxIdx.toShort)

        inputsCostTry.flatMap { case (ins, totalCost) =>

          // Cost of transaction initialization: we should read and parse all inputs and data inputs,
          // and also iterate through all outputs to check rules
          val initialCost: Long = boxesToSpend.size * params.inputCost +
          dataBoxes.size * params.dataInputCost +
          unsignedTx.outputCandidates.size * params.outputCost

          val context = new ErgoLikeContext(ErgoInterpreter.avlTreeFromDigest(stateContext.previousStateDigest),
            stateContext.sigmaLastHeaders,
            stateContext.sigmaPreHeader,
            transactionContext.dataBoxes,
            transactionContext.boxesToSpend,
            transactionContext.spendingTransaction,
            transactionContext.selfIndex,
            unsignedInput.extension,
            ValidationRules.currentSettings,
            params.maxBlockCost,
            initialCost
          )

          prove(inputBox.ergoTree, context, unsignedTx.messageToSign).flatMap { proverResult =>
            val newTC = totalCost + proverResult.cost
            if (newTC > context.costLimit)
              Failure(new Exception(s"Cost of transaction $unsignedTx exceeds limit ${context.costLimit}"))
            else
              Success((ins :+ Input(unsignedInput.boxId, proverResult)) -> newTC)
          }
        }
      }
      .map { case (inputs, _) =>
        new ErgoLikeTransaction(inputs, unsignedTx.dataInputs, unsignedTx.outputCandidates)
      }
  }

  /**
    * A method which is generating a commitment to randomness, which is a first step to prove
    * knowledge of a secret. Method checks whether secret is known to the prover, and returns
    * None if the secret is not known.
    *
    * @param pubkey - public image of a secret
    * @return Some((r, cmt)), a commitment to (secret) randomness "cmt" along with the randomness "r",
    *         if the secret corresponding to pubkey is known, None otherwise
    */
  def generateCommitmentFor(pubkey: SigmaBoolean): Option[(BigInteger, FirstProverMessage)] = {
    val idx = pubKeys.indexOf(pubkey)
    if (idx == -1) {
      None
    } else {
      pubkey match {
        case dl: ProveDlog =>
          Some(DLogInteractiveProver.firstMessage(dl))
        case dh: ProveDHTuple =>
          Some(DiffieHellmanTupleInteractiveProver.firstMessage(dh))
        case _ => None
      }
    }
  }

}

object ErgoProvingInterpreter {

  def apply(secrets: IndexedSeq[SecretKey], params: ErgoLikeParameters): ErgoProvingInterpreter =
    new ErgoProvingInterpreter(secrets, params)(new CompiletimeIRContext)

  def apply(rootSecret: ExtendedSecretKey, params: ErgoLikeParameters): ErgoProvingInterpreter =
    new ErgoProvingInterpreter(IndexedSeq(rootSecret), params)(new CompiletimeIRContext)

}
