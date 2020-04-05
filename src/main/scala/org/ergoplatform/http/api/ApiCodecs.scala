package org.ergoplatform.http.api

import io.circe._
import io.circe.syntax._
import org.ergoplatform.JsonCodecs
import org.ergoplatform.http.api.ApiEncoderOption.Detalization
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.RegisterId
import org.ergoplatform.mining.{groupElemFromBytes, groupElemToBytes}
import org.ergoplatform.nodeView.history.ErgoHistory.Difficulty
import org.ergoplatform.nodeView.wallet.IdUtils.EncodedTokenId
import org.ergoplatform.nodeView.wallet.persistence.WalletDigest
import org.ergoplatform.settings.Algos
import org.ergoplatform.wallet.Constants.ApplicationId
import org.ergoplatform.wallet.boxes.TrackedBox
import scorex.core.validation.ValidationResult
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.interpreter.CryptoConstants.EcPointType


trait ApiCodecs extends JsonCodecs {

  def fromValidation[T](validationResult: ValidationResult[T])
                       (implicit cursor: ACursor): Either[DecodingFailure, T] = {
    fromTry(validationResult.toTry)
  }

  implicit val bigIntEncoder: Encoder[BigInt] = { bigInt =>
    JsonNumber.fromDecimalStringUnsafe(bigInt.toString).asJson
  }

  implicit val difficultyEncoder: Encoder[Difficulty] = bigIntEncoder

  implicit val ecPointDecoder: Decoder[EcPointType] = { implicit cursor =>
    for {
      str <- cursor.as[String]
      bytes <- fromTry(Algos.decode(str))
    } yield groupElemFromBytes(bytes)
  }

  implicit val ecPointEncoder: Encoder[EcPointType] = { point: EcPointType =>
    groupElemToBytes(point).asJson
  }

  implicit val proveDlogEncoder: Encoder[ProveDlog] = _.pkBytes.asJson

  implicit val encodedTokenIdEncoder: Encoder[EncodedTokenId] = _.asJson

  implicit val balancesSnapshotEncoder: Encoder[WalletDigest] = { v =>
    import v._
    Json.obj(
      "height" -> height.asJson,
      "balance" -> walletBalance.asJson,
      "assets" -> walletAssetBalances.map(x => (x._1: String, x._2)).asJson
    )
  }

  // todo: this val is named "anyRegisterIdEncoder" because parent trait already contains
  // "registerIdEncoder" which is actually a KeyEncoder for NonMandatoryRegisterId
  implicit val anyRegisterIdEncoder: Encoder[RegisterId] = { regId =>
    s"R${regId.number}".asJson
  }

  // todo: see comment for "RegisterIdEncoder" above
  implicit val anyRegisterIdDecoder: Decoder[RegisterId] = { implicit cursor =>
    for {
      regId <- cursor.as[String]
      reg <- fromOption(ErgoBox.registerByName.get(regId))
    } yield reg
  }

  implicit val applicationIdEncoder: Encoder[ApplicationId] = {appId =>
    appId.toShort.asJson
  }

  implicit val applicationDecoder: Decoder[ApplicationId] = {c: HCursor =>
    ApplicationId @@ c.as[Short]
  }

  implicit def trackedBoxEncoder(implicit opts: Detalization): Encoder[TrackedBox] = { box =>
    val plainFields = Map(
      "spent" -> box.spendingStatus.spent.asJson,
      "onchain" -> box.chainStatus.onChain.asJson,
      "creationOutIndex" -> box.creationOutIndex.asJson,
      "inclusionHeight" -> box.inclusionHeightOpt.asJson,
      "spendingHeight" -> box.spendingHeightOpt.asJson,
      "applications" -> box.applicationStatuses.asJson,
      "box" -> box.box.asJson
    )

    val fieldsWithTx = if (opts.showDetails) {
      plainFields +
        ("creationTransaction" -> box.creationTxId.asJson) +
        ("spendingTransaction" -> box.spendingTxIdOpt.asJson)
    } else {
      plainFields +
        ("creationTransactionId" -> box.creationTxId.asJson) +
        ("spendingTransactionId" -> box.spendingTxIdOpt.asJson)
    }
    fieldsWithTx.asJson
  }
}

trait ApiEncoderOption

object ApiEncoderOption {

  abstract class Detalization(val showDetails: Boolean) extends ApiEncoderOption {
    implicit def implicitValue: Detalization = this
  }

  case object ShowDetails extends Detalization(true)

  case object HideDetails extends Detalization(false)

}
