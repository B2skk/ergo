package org.ergoplatform.api.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.syntax._
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, Input}
import org.ergoplatform.api.WalletApiRoute
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.wallet.{Pay2SAddress, PaymentRequest, PaymentRequestDecoder, PaymentRequestEncoder}
import org.ergoplatform.settings.ErgoSettings
import org.scalatest.{FlatSpec, Matchers}
import scorex.crypto.authds.ADKey
import sigmastate.Values
import sigmastate.Values.TrueLeaf
import sigmastate.interpreter.{ContextExtension, ProverResult}

class WalletApiRouteSpec extends FlatSpec
  with Matchers
  with ScalatestRouteTest
  with Stubs
  with FailFastCirceSupport {

  val prefix = "/wallet"

  val ergoSettigns = ErgoSettings.read(None)
  implicit val requestEncoder = new PaymentRequestEncoder(ergoSettigns)
  val route = WalletApiRoute(readersRef, nodeViewRef, settings).route

  it should "generate transaction" in {
    val amount = 100L
    val request = PaymentRequest(Pay2SAddress(Values.FalseLeaf), amount, None, None)
    Post(prefix + "/transaction/generate", Seq(request).asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[ErgoTransaction].outputs.head.value shouldEqual amount
    }
  }

  it should "generate & send transaction" in {
    val request = PaymentRequest(Pay2SAddress(Values.FalseLeaf), 100L, None, None)
    Post(prefix + "/transaction/payment", Seq(request).asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should not be empty
    }
  }

  it should "return addresses" in {
    Get(prefix + "/addresses") ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

}
