package zio.openfeature.conformance.bdd.library

import java.util.concurrent.atomic.AtomicReference

import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** Hand-rolled providers for the behaviours the testkit provider cannot express — a `null` string, an observation of
  * the default the evaluator handed down, and a provider that predates SDK 1.22.0.
  *
  * They exist here rather than in `testkit` deliberately: each one models a *misbehaving or legacy third party*, which
  * is not something the published testkit should offer as a fixture.
  */
object LibraryProviders {

  private def meta(nm: String): Metadata = new Metadata { def getName: String = nm }

  /** Answers `null` from the string resolver. #356 runs `FlagType.decode` on the extracted value, so this must surface
    * as a typed `TypeMismatch` instead of flowing through as a `null` flag value.
    */
  final class NullStringProvider extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = meta("NullString")
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](null, "TARGETING_MATCH")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Records the default each resolver was handed and answers `FLAG_NOT_FOUND` for every key.
    *
    * #364: the object path used to send an empty `Value` rather than the caller's default and to lose the provider's
    * error code on the way back. `sawObjectDefault` is the only place the first half is observable.
    */
  final class DefaultRecordingProvider extends FeatureProvider {
    val sawObjectDefault: AtomicReference[Value]   = new AtomicReference[Value](null)
    val sawStringDefault: AtomicReference[String]  = new AtomicReference[String](null)
    val sawLongDefault: AtomicReference[java.lang.Long] = new AtomicReference[java.lang.Long](null)

    private def missing[T](k: String, d: T): ProviderEvaluation[T] =
      ProviderEvaluations.error(d, OFErrorCode.FLAG_NOT_FOUND, s"Flag '$k' does not exist")

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = meta("DefaultRecording")
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = missing(k, d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      sawStringDefault.set(d)
      missing(k, d)
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = missing(k, d)
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext) = {
      sawLongDefault.set(d)
      missing(k, d)
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) = missing(k, d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = {
      sawObjectDefault.set(d)
      missing(k, d)
    }
  }

  /** A third-party provider written against SDK &lt; 1.22.0: it has no `getLongEvaluation` override, so the interface
    * default routes `Long` through its *double* resolver. The two resolvers answer different values, which is what
    * makes the routing observable end-to-end (#333).
    */
  final class LegacyLongProvider extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = meta("LegacyLong")
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](Integer.valueOf(7), "int-variant", "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](java.lang.Double.valueOf(99.0), "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Constructs successfully but knows no flags at all — the "constructed ≠ healthy" case `verify` exists to reject
    * (#349). Every key answers `FLAG_NOT_FOUND`, including the sentinel a `Verify.flagExists` check probes for.
    */
  final class EmptyProvider(nm: String) extends FeatureProvider {
    private def missing[T](k: String, d: T): ProviderEvaluation[T] =
      ProviderEvaluations.error(d, OFErrorCode.FLAG_NOT_FOUND, s"Flag '$k' does not exist")

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = meta(nm)
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = missing(k, d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = missing(k, d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = missing(k, d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = missing(k, d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = missing(k, d)
  }
}
