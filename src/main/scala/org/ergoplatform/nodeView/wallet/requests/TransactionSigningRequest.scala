package org.ergoplatform.nodeView.wallet.requests

import io.circe.{Decoder, Encoder, Json}
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.wallet.secrets.{DhtSecretWrapper, DlogSecretWrapper, PrimitiveSecretKey}

trait Hint

case class OneTimeSecret(key: PrimitiveSecretKey) extends Hint

case class TransactionSigningRequest(tx: ErgoTransaction, hints: Seq[Hint]){
  lazy val dlogs: Seq[DlogSecretWrapper] = hints.flatMap{ h => h match{
    case OneTimeSecret(d: DlogSecretWrapper) => Some(d)
    case _ => None
  }}

  lazy val dhts: Seq[DhtSecretWrapper] = hints.flatMap{ h => h match{
    case OneTimeSecret(d: DhtSecretWrapper) => Some(d)
    case _ => None
  }}


}

// TODO
// {
//   "tx": ErgoTransaction,
//   "secrets": {
//     "dlog": ["base16" (32 bytes BigInt), ...], // DLogProverInput(SigmaDsl.BigInt(new BigInteger(1, Base16.decode("base16").get)))
//     "dht": [["base16" (32 bytes BigInt), ["base16" (33 bytes compressed GroupElement), "same", "same", "same"]], ...] // DiffieHellmanTupleProverInput(..., ProveDHTuple(...))
//   }
// }
object TransactionSigningRequest extends ApiCodecs {
  import io.circe.syntax._

  private implicit val txEncoder = ErgoTransaction.transactionEncoder
  private implicit val txDecoder = ErgoTransaction.transactionDecoder

  implicit val encoder: Encoder[TransactionSigningRequest] = { tsr =>
    Json.obj(
      "tx" -> tsr.tx.asJson,
      "secrets" -> Json.obj(
        "dlog" -> tsr.dlogs.asJson,
        "dht" -> tsr.dhts.asJson
      )
    )
  }

  implicit val decoder: Decoder[TransactionSigningRequest] = { cursor =>
    for {
      tx <- cursor.downField("tx").as[ErgoTransaction]
      dlogs <- cursor.downField("secrets").downField("dlog").as[Option[Seq[DlogSecretWrapper]]]
      dhts <- cursor.downField("secrets").downField("dht").as[Option[Seq[DlogSecretWrapper]]]
    } yield TransactionSigningRequest(tx, (dlogs.getOrElse(Seq.empty) ++ dhts.getOrElse(Seq.empty)).map(OneTimeSecret.apply))
  }
}
