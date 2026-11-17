package zio.openfeature.simulation

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI}
import zio._
import zio.openfeature._
import zio.openfeature.extras._
import zio.openfeature.testkit.HookCall

/** What an evaluation produced, normalized so the model can predict it. */
final case class Outcome(value: Option[String], reason: String, variant: Option[String], code: Option[String]) {
  def isSuccess: Boolean        = code.isEmpty
  override def toString: String =
    code.fold(s"${value.getOrElse("?")}/$reason/${variant.getOrElse("-")}")(c => s"ERR($c)")
}

/** A built stack: the provider to install, and handles on its leaves and caches by id. */
final case class Built(provider: OFFeatureProvider, leaves: Map[Int, SimLeaf], caches: Map[Int, CachingProvider])

object Harness {

  val breakerConfig: Int => CircuitBreakerProviderConfig = t =>
    CircuitBreakerProviderConfig.default
      .withFailureThreshold(t)
      .withResetTimeout(Duration.Infinity)
      .withEvaluationTimeout(Duration.Infinity)
      .withStalePolicy(StalePolicy.Ignore)
      .withStateCheckInterval(Duration.Infinity)

  def build(spec: Spec): Built = {
    var leaves                         = Map.empty[Int, SimLeaf]
    var caches                         = Map.empty[Int, CachingProvider]
    def go(s: Spec): OFFeatureProvider = s match {
      case Spec.Leaf(id, init) =>
        val l = new SimLeaf(s"L$id")
        init.foreach { case (k, v) => l.flags.put(k, v) }
        leaves += id -> l
        l
      case Spec.Cache(id, in) =>
        val c = CachingProvider(go(in), CachingConfig.default.withTtl(Duration.Infinity))
        caches += id -> c
        c
      case Spec.Breaker(_, t, _, in) => CircuitBreakerProvider(go(in), breakerConfig(t))
      case Spec.Deferred(in)         =>
        val inner = go(in)
        DeferredProvider("deferred")(() => inner)
      case Spec.Widen(in)     => IntegerWideningLongProvider(go(in))
      case Spec.Multi(fs, cs) =>
        FeatureFlags.multiProvider(
          cs.map(go),
          if (fs) MultiProviderStrategy.FirstSuccessful else MultiProviderStrategy.FirstMatch
        )
    }
    val p = go(spec)
    Built(p, leaves, caches)
  }

  /** A FeatureFlags on a private API and domain, owning `provider`. */
  def install(provider: OFFeatureProvider): ZIO[Scope, Throwable, (FeatureFlags, FeatureFlagsAdmin)] =
    FeatureFlags
      .fromProvider(
        provider,
        FeatureFlagsConfig.default
          .withDomain(s"sim-${java.util.UUID.randomUUID()}")
          .withApiOwnership(ApiOwnership.Owned),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
      .map(env => (env.get[FeatureFlags], env.get[FeatureFlagsAdmin]))

  private def reasonName(r: ResolutionReason): String = r match {
    case ResolutionReason.Static         => "STATIC"
    case ResolutionReason.TargetingMatch => "TARGETING_MATCH"
    case ResolutionReason.Cached         => "CACHED"
    case ResolutionReason.Error          => "ERROR"
    case ResolutionReason.Default        => "DEFAULT"
    case other                           => other.toString
  }

  def codeName(c: ErrorCode): String = c match {
    case ErrorCode.ProviderNotReady    => "PROVIDER_NOT_READY"
    case ErrorCode.ProviderFatal       => "PROVIDER_FATAL"
    case ErrorCode.FlagNotFound        => "FLAG_NOT_FOUND"
    case ErrorCode.ParseError          => "PARSE_ERROR"
    case ErrorCode.TypeMismatch        => "TYPE_MISMATCH"
    case ErrorCode.TargetingKeyMissing => "TARGETING_KEY_MISSING"
    case ErrorCode.InvalidContext      => "INVALID_CONTEXT"
    case ErrorCode.General             => "GENERAL"
  }

  def renderValue(v: Any): String = v match {
    case m: Map[?, ?] => m.toList.map { case (k, x) => s"$k=$x" }.sorted.mkString("{", ",", "}")
    case Tier(n)      => n
    case Some(x)      => renderValue(x)
    case None         => "None"
    case other        => String.valueOf(other)
  }

  def normalize[A](r: FlagResolution[A]): Outcome =
    r.errorCode match {
      case Some(c)                                    => Outcome(None, "ERROR", None, Some(codeName(c)))
      case None if r.reason == ResolutionReason.Error => Outcome(None, "ERROR", None, Some("?"))
      case None => Outcome(Some(renderValue(r.value)), reasonName(r.reason), r.variant, None)
    }

  /** The live read for a kind. */
  def read(ff: FeatureFlags, key: String, kind: Kind, ctx: EvaluationContext): UIO[Outcome] = kind match {
    case Kind.KBool    => ff.resolveOrDefault[Boolean](key, false, ctx).map(normalize)
    case Kind.KStr     => ff.resolveOrDefault[String](key, "dflt", ctx).map(normalize)
    case Kind.KInt     => ff.resolveOrDefault[Int](key, -99, ctx).map(normalize)
    case Kind.KLong(d) => ff.resolveOrDefault[Long](key, d, ctx).map(normalize)
    case Kind.KDbl     => ff.resolveOrDefault[Double](key, -9.5, ctx).map(normalize)
    case Kind.KObj     => ff.resolveOrDefault[Map[String, Any]](key, Map.empty[String, Any], ctx).map(normalize)
    case Kind.KTier    => ff.resolveOrDefault[Tier](key, Tier("none"), ctx).map(normalize)
    case Kind.KOptStr  => ff.resolveOrDefault[Option[String]](key, None, ctx).map(normalize)
  }

  /** The value an override is given as, for a kind. */
  def overrideValue(s: Stored): Any = s match {
    case Stored.SBool(v) => v
    case Stored.SStr(v)  => v
    case Stored.SInt(v)  => v
    case Stored.SLong(v) => v
    case Stored.SDbl(v)  => v
    case Stored.SObj(m)  => m
    case Stored.Echo     => "echo"
  }

  /** The library-level outcome of a provider-level result for a kind. */
  def lift(kind: Kind, r: PResult): Outcome = r match {
    case PResult.Ok(v, reason, variant) =>
      val value = kind match {
        case Kind.KTier => v.toUpperCase
        case _          => v
      }
      Outcome(Some(value), reason, Some(variant), None)
    case PResult.Ret(c) => Outcome(None, "ERROR", None, Some(c))
    case PResult.Thr(c) => Outcome(None, "ERROR", None, Some(c))
  }

  def overrideOutcome(kind: Kind, s: Stored): Outcome =
    Outcome(Some(lift(kind, PResult.Ok(Model.renderStored(s, ""), "CACHED", "")).value.get), "CACHED", None, None)

  def fitsInt(kind: Kind): Boolean = kind match {
    case Kind.KLong(d) => d.isValidInt
    case _             => true
  }

  /** The hook stages one provider-reaching evaluation must produce. */
  def expectedStages(o: Outcome): List[String] =
    List("before", if (o.isSuccess) "after" else "error", "finally")

  def stages(calls: Chunk[HookCall]): List[String] = calls.toList.map {
    case _: HookCall.Before  => "before"
    case _: HookCall.After   => "after"
    case _: HookCall.Error   => "error"
    case _: HookCall.Finally => "finally"
  }
}
