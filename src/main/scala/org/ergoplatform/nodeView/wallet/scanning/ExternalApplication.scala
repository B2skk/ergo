package org.ergoplatform.nodeView.wallet.scanning

import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.wallet.Constants.ApplicationId
import scorex.core.serialization.ScorexSerializer
import scorex.util.serialization.{Reader, Writer}

import scala.util.{Failure, Success, Try}

/**
  * A class which encodes a request to create an application.
  *
  * @param appId         - unique identifier of an application in the local system
  * @param appName       - application description (255 bytes in UTF-8 encoding max)
  * @param trackingRule  - a predicate to scan the blockchain for specific application-related boxes
  */
case class ExternalApplication(appId: ApplicationId,
                               appName: String,
                               trackingRule: ScanningPredicate)

object ExternalApplication {

  val MaxAppNameLength = 255
}

/**
  * A class which encodes a request to create an application.
  *
  * @param appName       - application description (255 bytes in UTF-8 encoding max)
  * @param trackingRule  - a predicate to scan the blockchain for specific application-related boxes
  *
  */

case class ExternalAppRequest(appName: String, trackingRule: ScanningPredicate) {
  def toApp(appId: ApplicationId): Try[ExternalApplication] = {
    if (appName.getBytes("UTF-8").length > ExternalApplication.MaxAppNameLength) {
      Failure(new Exception(s"Too long application name: $appName"))
    } else {
      Success(ExternalApplication(appId, appName, trackingRule))
    }
  }
}

object ExternalApplicationSerializer extends ScorexSerializer[ExternalApplication] {
  override def serialize(app: ExternalApplication, w: Writer): Unit = {
    w.putShort(app.appId)
    w.putShortString(app.appName)
    ScanningPredicateSerializer.serialize(app.trackingRule, w)
  }

  override def parse(r: Reader): ExternalApplication = {
    val appId = ApplicationId @@ r.getShort()
    val appName = r.getShortString()
    val sp = ScanningPredicateSerializer.parse(r)
    ExternalApplication(appId, appName, sp)
  }
}

object ExternalApplicationJsonCodecs extends ApiCodecs {

  import ScanningPredicateJsonCodecs._
  import io.circe._, io.circe.generic.semiauto._

  implicit val appReqDecoder: Decoder[ExternalAppRequest] = deriveDecoder[ExternalAppRequest]
  implicit val appReqEncoder: Encoder[ExternalAppRequest] = deriveEncoder[ExternalAppRequest]

  implicit val appDecoder: Decoder[ExternalApplication] = deriveDecoder[ExternalApplication]
  implicit val appEncoder: Encoder[ExternalApplication] = deriveEncoder[ExternalApplication]
}
