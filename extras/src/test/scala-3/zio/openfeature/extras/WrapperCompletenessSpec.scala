package zio.openfeature.extras

import dev.openfeature.sdk.FeatureProvider
import zio.test.*

/** Every delegating wrapper must forward the whole `FeatureProvider` surface.
  *
  * The SDK adds capabilities as **default methods** on the interface, so a wrapper that forwards only the surface it
  * was written against keeps compiling while silently answering from the interface default instead of the provider it
  * wraps. SDK 1.22.0 shipped three at once (`getLongEvaluation`, `initialize(ctx, domain)`, `isDomainScoped`), and
  * every one of them was being stripped — a `CachingProvider` around a provider with native 64-bit resolution routed
  * Long evaluations through the double-backed default.
  *
  * Reflection is the only check that fails when the *next* default method lands, rather than when someone happens to
  * notice.
  */
object WrapperCompletenessSpec extends ZIOSpecDefault:

  /** Methods a wrapper may legitimately not forward, each with the reason it is safe. */
  private val exempt: Map[String, String] = Map(
    "getMetadata" -> "a wrapper reports its own name, not the wrapped provider's",
    "getState"    -> "deprecated in 1.22.0; SDK owns status. Removal tracked in #332"
  )

  /** Name AND parameter types. Matching on name alone is not enough: `initialize` is overloaded, so a wrapper declaring
    * only `initialize(ctx)` would satisfy a name-based check while silently dropping the bound domain from
    * `initialize(ctx, domain)` — one of the three methods this spec exists to catch.
    */
  private type Sig = (String, List[Class[?]])

  private def render(s: Sig): String = s"${s._1}(${s._2.map(_.getSimpleName).mkString(", ")})"

  /** Instance methods contributed by the OpenFeature interface hierarchy, ignoring `Object`. */
  private def surface: List[Sig] =
    classOf[FeatureProvider].getMethods.toList
      .filterNot(_.getDeclaringClass == classOf[Object])
      .filterNot(m => java.lang.reflect.Modifier.isStatic(m.getModifiers))
      .map(m => (m.getName, m.getParameterTypes.toList))
      .distinct
      .sortBy(render)

  /** Methods the class itself declares, minus bridge/synthetic ones. Scala 3 emits a public `ACC_BRIDGE |
    * ACC_SYNTHETIC` forwarder for every Java default method a class inherits without overriding (body: `invokespecial`
    * the interface default), so without this filter a wrapper that forgets e.g. `track` still "declares" it and the
    * check passes vacuously (#448). A real override is never bridge or synthetic.
    */
  private def declaredSigs(cls: Class[?]): Set[Sig] =
    cls.getDeclaredMethods
      .filterNot(m => m.isBridge || m.isSynthetic)
      .map(m => (m.getName, m.getParameterTypes.toList))
      .toSet

  private def missing(cls: Class[?]): List[Sig] =
    val declared = declaredSigs(cls)
    surface.filterNot(declared.contains).filterNot(s => exempt.contains(s._1))

  private def check(cls: Class[?]): TestResult =
    val gaps = missing(cls)
    assertTrue(gaps.isEmpty) ?? s"${cls.getSimpleName} does not forward: ${gaps.map(render).mkString(", ")}"

  def spec = suite("wrapper completeness")(
    test("CachingProvider forwards the whole FeatureProvider surface")(check(classOf[CachingProvider])),
    test("CircuitBreakerProvider forwards the whole FeatureProvider surface")(
      check(classOf[CircuitBreakerProvider])
    ),
    test("DeferredProvider forwards the whole FeatureProvider surface")(check(classOf[DeferredProvider])),
    test("IntegerWideningLongProvider forwards the whole FeatureProvider surface")(
      check(classOf[IntegerWideningLongProvider])
    ),
    // Guards the guard (#448): a wrapper that overrides everything except a Java default method must be reported.
    test("a wrapper that does not override track is reported as missing it") {
      assertTrue(missing(classOf[WrapperCompletenessSpec.ForgetsTrack]).map(_._1) == List("track"))
    },
    // Guards the guard: if the reflection ever returns an empty surface (an SDK repackaging, a
    // shading step, a JPMS change), every check above would pass vacuously and the suite would
    // report success while verifying nothing.
    test("the reflected surface is non-empty and contains the methods 1.22.0 added") {
      val names = surface.map(_._1).toSet
      assertTrue(
        surface.nonEmpty,
        names.contains("getLongEvaluation"),
        names.contains("isDomainScoped"),
        // The two-argument overload specifically — the one a name-based check cannot see.
        surface.exists(s => s._1 == "initialize" && s._2.sizeIs == 2)
      )
    }
  )

  /** Fixture for the guard-the-guard test: forwards every FeatureProvider method except `track`. */
  final class ForgetsTrack(d: FeatureProvider) extends FeatureProvider:
    override def getMetadata: dev.openfeature.sdk.Metadata                                  = d.getMetadata
    override def getProviderHooks: java.util.List[dev.openfeature.sdk.Hook[?]]              = d.getProviderHooks
    override def initialize(c: dev.openfeature.sdk.EvaluationContext): Unit                 = d.initialize(c)
    override def initialize(c: dev.openfeature.sdk.EvaluationContext, domain: String): Unit = d.initialize(c, domain)
    override def isDomainScoped(): Boolean                                                  = d.isDomainScoped()
    override def shutdown(): Unit                                                           = d.shutdown()
    override def getBooleanEvaluation(k: String, v: java.lang.Boolean, c: dev.openfeature.sdk.EvaluationContext) =
      d.getBooleanEvaluation(k, v, c)
    override def getStringEvaluation(k: String, v: String, c: dev.openfeature.sdk.EvaluationContext) =
      d.getStringEvaluation(k, v, c)
    override def getIntegerEvaluation(k: String, v: java.lang.Integer, c: dev.openfeature.sdk.EvaluationContext) =
      d.getIntegerEvaluation(k, v, c)
    override def getDoubleEvaluation(k: String, v: java.lang.Double, c: dev.openfeature.sdk.EvaluationContext) =
      d.getDoubleEvaluation(k, v, c)
    override def getLongEvaluation(k: String, v: java.lang.Long, c: dev.openfeature.sdk.EvaluationContext) =
      d.getLongEvaluation(k, v, c)
    override def getObjectEvaluation(
      k: String,
      v: dev.openfeature.sdk.Value,
      c: dev.openfeature.sdk.EvaluationContext
    ) =
      d.getObjectEvaluation(k, v, c)
