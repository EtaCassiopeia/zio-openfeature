package zio.openfeature.testkit

import dev.openfeature.sdk.FeatureProvider
import zio.test.*

/** The `extras` counterpart of this spec carries the full rationale. Duplicated rather than shared because `extras` and
  * `testkit` are sibling modules — neither depends on the other, and adding a dependency between them to host ~20 lines
  * of reflection would be a worse trade than the copy.
  */
object TestkitWrapperCompletenessSpec extends ZIOSpecDefault:

  private val exempt: Map[String, String] = Map(
    "getMetadata" -> "a wrapper reports its own name, not the wrapped provider's",
    "getState"    -> "deprecated in 1.22.0; SDK owns status. Removal tracked in #332"
  )

  /** Name AND parameter types — `initialize` is overloaded, so a name-only check would miss a wrapper that declares
    * `initialize(ctx)` but drops `initialize(ctx, domain)`.
    */
  private type Sig = (String, List[Class[?]])

  private def render(s: Sig): String = s"${s._1}(${s._2.map(_.getSimpleName).mkString(", ")})"

  private def surface: List[Sig] =
    classOf[FeatureProvider].getMethods.toList
      .filterNot(_.getDeclaringClass == classOf[Object])
      .filterNot(m => java.lang.reflect.Modifier.isStatic(m.getModifiers))
      .map(m => (m.getName, m.getParameterTypes.toList))
      .distinct
      .sortBy(render)

  private def check(cls: Class[?]): TestResult =
    val declared = cls.getDeclaredMethods.map(m => (m.getName, m.getParameterTypes.toList)).toSet
    val missing  = surface.filterNot(declared.contains).filterNot(s => exempt.contains(s._1))
    assertTrue(missing.isEmpty) ??
      s"${cls.getSimpleName} does not forward: ${missing.map(render).mkString(", ")}"

  def spec = suite("testkit wrapper completeness")(
    test("CachingReasonProvider forwards the whole FeatureProvider surface")(
      check(classOf[CachingReasonProvider])
    ),
    test("the reflected surface is non-empty and contains the methods 1.22.0 added") {
      val names = surface.map(_._1).toSet
      assertTrue(
        surface.nonEmpty,
        names.contains("getLongEvaluation"),
        names.contains("isDomainScoped"),
        surface.exists(s => s._1 == "initialize" && s._2.sizeIs == 2)
      )
    }
  )
