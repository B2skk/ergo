package org.ergoplatform.settings

import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.{Extension, ExtensionCandidate}
import org.ergoplatform.validation.SigmaValidationSettings
import scorex.core.serialization.{BytesSerializable, ScorexSerializer}
import scorex.core.validation.{ModifierValidator, TaggedValidationRules, ValidationResult}
import scorex.util.serialization.{Reader, Writer}

import scala.util.Try

/**
  * Ergo configuration of validation.
  *
  * Specifies the strategy to be used (fail-fast) and validation rules with their statuses.
  * Contains delta from the initial validation rules `updateFromInitial` as well as calculated current rules,
  * that might also be computed by applying `updateFromInitial` to `ErgoValidationSettings.initial`
  *
  * @param rules             - map from rule id to it's current status
  * @param sigmaSettings     - validation settings of sigma script
  * @param updateFromInitial - update from initial ErgoValidationSettings
  */
case class ErgoValidationRules(rules: Map[Short, RuleStatus],
                               sigmaSettings: SigmaValidationSettings,
                               updateFromInitial: ErgoValidationSettingsUpdate)
  extends TaggedValidationRules with BytesSerializable {

  override type M = ErgoValidationRules

  override def getError(id: Short, details: String): ValidationResult.Invalid = {
    rules.get(id).map(_.error(details)).getOrElse(ModifierValidator.fatal("Unknown message"))
  }

  override def isActive(id: Short): Boolean = {
    rules.get(id).forall(_.isActive)
  }

  def updated(u: ErgoValidationSettingsUpdate): ErgoValidationRules = {
    val newSigmaSettings = u.statusUpdates.foldLeft(sigmaSettings)((s, u) => s.updated(u._1, u._2))
    val newRules = updateRules(rules, u.rulesToDisable)
    val totalUpdate = updateFromInitial ++ u

    ErgoValidationRules(newRules, newSigmaSettings, totalUpdate)
  }

  /**
    * Disable sequence of rules
    */
  private def updateRules(rules: Map[Short, RuleStatus],
                          toDisable: Seq[Short]): Map[Short, RuleStatus] = if (toDisable.nonEmpty) {
    rules.map { currentRule =>
      if (toDisable.contains(currentRule._1)) {
        currentRule._1 -> currentRule._2.copy(isActive = false)
      } else {
        currentRule
      }
    }
  } else {
    rules
  }

  /**
    * Generates extension candidate with serialized ErgoValidationSettings in it
    */
  def toExtensionCandidate: ExtensionCandidate = {
    if (isInitial) {
      ExtensionCandidate(Seq())
    } else {
      val fields = bytes.sliding(Extension.FieldValueMaxSize, Extension.FieldValueMaxSize).zipWithIndex.map { case (b, i) =>
        Array(Extension.ValidationRulesPrefix, i.toByte) -> b
      }
      ExtensionCandidate(fields.toSeq)
    }
  }

  def isInitial: Boolean = this == ErgoValidationRules.initial

  override def serializer: ScorexSerializer[ErgoValidationRules] = ErgoValidationSettingsSerializer

  /**
    * We only cares about `updateFromInitial`, as far as `rules` and `sigmaSettings` may be
    * deterministically computed from it and initial validation settings.
    */
  override def equals(obj: Any): Boolean = obj match {
    case p: ErgoValidationRules => p.updateFromInitial == updateFromInitial
    case _ => false
  }

  override def hashCode(): Int = java.util.Objects.hash(updateFromInitial)
}

object ErgoValidationRules {

  /**
    * Initial validation settings.
    * To be used during genesis state creation or to perform static checks that are not allowed
    * to be deactivated via soft-forks.
    */
  val initial: ErgoValidationRules = new ErgoValidationRules(ValidationRules.rulesSpec,
    org.ergoplatform.validation.ValidationRules.currentSettings,
    ErgoValidationSettingsUpdate.empty)

  /**
    * Extracts ErgoValidationSettings from extension section of the block
    */
  def parseExtension(extension: ExtensionCandidate): Try[ErgoValidationRules] = Try {
    val values = extension.fields
      .filter(_._1(0) == Extension.ValidationRulesPrefix)
      .sortBy(_._1(1))
      .map(_._2)
    if (values.isEmpty) {
      ErgoValidationRules.initial
    } else {
      val bytes = scorex.core.utils.concatBytes(values)
      ErgoValidationSettingsSerializer.parseBytes(bytes)
    }
  }

}

object ErgoValidationSettingsSerializer extends ScorexSerializer[ErgoValidationRules] with ApiCodecs {
  override def serialize(obj: ErgoValidationRules, w: Writer): Unit = {
    ErgoValidationSettingsUpdateSerializer.serialize(obj.updateFromInitial, w)
  }

  override def parse(r: Reader): ErgoValidationRules = {
    val updateFromInitial = ErgoValidationSettingsUpdateSerializer.parse(r)
    ErgoValidationRules.initial.updated(updateFromInitial)
  }

}