package org.ergoplatform.modifiers.history

import org.ergoplatform.utils.generators.{ChainGenerator, ErgoGenerators}
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scorex.util.ModifierId

class PoPowAlgosSpec
  extends PropSpec
    with Matchers
    with ChainGenerator
    with ErgoGenerators
    with GeneratorDrivenPropertyChecks {

  import PoPowAlgos._

  private val ChainLength = 10

  property("updateInterlinks") {
    val chain = genChain(ChainLength)
    val genesis = chain.head
    val interlinks = chain.foldLeft(Seq.empty[Seq[ModifierId]]) { case (acc, b) =>
      acc :+ (if (acc.isEmpty) updateInterlinks(b.header, Seq.empty) else updateInterlinks(b.header, acc.last))
    }

    interlinks.foreach { links =>
      links.head shouldEqual genesis.header.id
      links.tail should not contain genesis.header.id
    }

    interlinks.zipWithIndex.foreach { case (links, idx) =>
      if (idx > 0) links.size >= interlinks(idx - 1).size shouldBe true
    }
  }

  property("packInterlinks") {
    val diffInterlinks = Gen.listOfN(255, modifierIdGen).sample.get
    val modId = modifierIdGen.sample.get
    val sameInterlinks = List.fill(255)(modId)
    val packedDiff = PoPowAlgos.interlinksToExtension(diffInterlinks).fields
    val packedSame = PoPowAlgos.interlinksToExtension(sameInterlinks).fields

    packedDiff.map(_._1.last).toSet.size shouldEqual diffInterlinks.size
    packedSame.map(_._1.last).toSet.size shouldEqual 1
  }

  property("unpackInterlinks") {
    val interlinks = Gen.listOfN(255, modifierIdGen).sample.get
    val packed = PoPowAlgos.interlinksToExtension(interlinks).fields
    val improperlyPacked = packed.map(x => x._1 -> (x._2 :+ (127: Byte)))

    val unpackedTry = unpackInterlinks(packed)

    unpackedTry shouldBe 'success
    unpackInterlinks(improperlyPacked) shouldBe 'failure

    unpackedTry.get shouldEqual interlinks
  }

  property("0 level is always valid for any block") {
    val chain = genChain(10)
    chain.foreach(x => maxLevelOf(x.header) >= 0 shouldBe true)
  }

  property("lowestCommonAncestor") {
    val chain0 = genChain(10)
    val branchPoint = chain0(5)
    val chain1 = chain0.take(5) ++ genChain(5, branchPoint)

    lowestCommonAncestor(chain0.map(_.header), chain1.map(_.header)) shouldBe Some(branchPoint.header)
  }

  property("bestArg - always equal for equal proofs") {
    val chain0 = genChain(100).map(b => PoPowHeader(b.header, unpackInterlinks(b.extension.fields).get))
    val proof0 = prove(chain0)(settings.nodeSettings.poPowSettings.params)
    val chain1 = genChain(100).map(b => PoPowHeader(b.header, unpackInterlinks(b.extension.fields).get))
    val proof1 = prove(chain1)(settings.nodeSettings.poPowSettings.params)
    val m = settings.nodeSettings.poPowSettings.params.m

    proof0.prefix.chain.size shouldEqual proof1.prefix.chain.size

    bestArg(proof0.prefix.chain.map(_.header))(m) shouldEqual bestArg(proof1.prefix.chain.map(_.header))(m)
  }

  property("bestArg - always greater for better proof") {
    val chain0 = genChain(100).map(b => PoPowHeader(b.header, unpackInterlinks(b.extension.fields).get))
    val proof0 = prove(chain0)(settings.nodeSettings.poPowSettings.params)
    val chain1 = genChain(70).map(b => PoPowHeader(b.header, unpackInterlinks(b.extension.fields).get))
    val proof1 = prove(chain1)(settings.nodeSettings.poPowSettings.params)
    val m = settings.nodeSettings.poPowSettings.params.m

    proof0.prefix.chain.size > proof1.prefix.chain.size shouldBe true

    bestArg(proof0.prefix.chain.map(_.header))(m) > bestArg(proof1.prefix.chain.map(_.header))(m) shouldBe true
  }

}
