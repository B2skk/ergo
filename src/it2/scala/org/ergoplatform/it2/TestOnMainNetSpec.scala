package org.ergoplatform.it2

import com.typesafe.config.Config
import org.ergoplatform.it.api.NodeApi.NodeInfo
import org.ergoplatform.it.container.{IntegrationSuite, Node}
import org.scalatest.{FreeSpec, OptionValues}

import scala.async.Async
import scala.concurrent.Await
import scala.concurrent.duration._

class TestOnMainNetSpec
  extends FreeSpec
    with IntegrationSuite
    with OptionValues {

  val nodeConfig: Config = nodeSeedConfigs.head.withFallback(nonGeneratingPeerConfig)
  val node: Node = docker.startMainNetNodeYesImSure(nodeConfig).get

  "Start a node on mainnet and wait for a full sync" in {
    val result = Async.async {
      Async.await(node.waitFor[NodeInfo](
        _.info,
        nodeInfo => {
          nodeInfo.bestBlockHeightOpt.exists(nodeInfo.bestHeaderHeightOpt.contains)
        },
        60.second
      ))
    }
    Await.result(result, 120.minutes)
  }

}
