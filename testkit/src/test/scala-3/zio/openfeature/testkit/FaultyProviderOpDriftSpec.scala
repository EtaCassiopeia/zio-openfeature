package zio.openfeature.testkit

import dev.openfeature.sdk.{EventProvider, FeatureProvider}
import java.lang.reflect.{Method, Modifier}
import zio.test.*

/** SDK drift guard for [[FaultyProvider]]: every public method of `FeatureProvider` and `EventProvider`, except the
  * `emit*` family, must have an [[Op]] and an override in `FaultyProvider`. A method the SDK adds would otherwise fall
  * through to its interface default, unscripted and unrecorded.
  */
object FaultyProviderOpDriftSpec extends ZIOSpecDefault:

  /** The Java method name each op stands for. */
  private val methodOp: Map[String, Op] = Map(
    "getMetadata"          -> Op.GetMetadata,
    "getProviderHooks"     -> Op.GetProviderHooks,
    "getState"             -> Op.GetState,
    "initialize"           -> Op.Initialize,
    "shutdown"             -> Op.Shutdown,
    "isDomainScoped"       -> Op.IsDomainScoped,
    "track"                -> Op.Track,
    "getBooleanEvaluation" -> Op.EvaluateBoolean,
    "getStringEvaluation"  -> Op.EvaluateString,
    "getIntegerEvaluation" -> Op.EvaluateInteger,
    "getLongEvaluation"    -> Op.EvaluateLong,
    "getDoubleEvaluation"  -> Op.EvaluateDouble,
    "getObjectEvaluation"  -> Op.EvaluateObject
  )

  private type Sig = (String, List[Class[?]])

  private def render(s: Sig): String = s"${s._1}(${s._2.map(_.getSimpleName).mkString(", ")})"

  private def sig(m: Method): Sig = (m.getName, m.getParameterTypes.toList)

  private def surface: List[Sig] =
    (classOf[FeatureProvider].getMethods.toList ++ classOf[EventProvider].getDeclaredMethods.toList)
      .filter(m => Modifier.isPublic(m.getModifiers) && !Modifier.isStatic(m.getModifiers))
      .filterNot(_.getDeclaringClass == classOf[Object])
      .filterNot(_.getName.startsWith("emit"))
      .map(sig)
      .distinct
      .sortBy(render)

  def spec = suite("FaultyProvider Op drift")(
    test("every FeatureProvider / EventProvider method has an Op") {
      val unmapped = surface.filterNot(s => methodOp.contains(s._1))
      assertTrue(unmapped.isEmpty) ?? s"no Op for: ${unmapped.map(render).mkString(", ")}"
    },
    test("FaultyProvider overrides every one of them") {
      val declared = classOf[FaultyProvider[?]].getDeclaredMethods.map(sig).toSet
      val missing  = surface.filterNot(declared.contains)
      assertTrue(missing.isEmpty) ?? s"FaultyProvider does not override: ${missing.map(render).mkString(", ")}"
    },
    test("every Op stands for a method that still exists") {
      val names = surface.map(_._1).toSet
      val stale = Op.values.filterNot(op => methodOp.collect { case (n, o) if o == op => n }.exists(names.contains))
      assertTrue(stale.isEmpty, Op.values.distinct.size == methodOp.size)
    }
  )
