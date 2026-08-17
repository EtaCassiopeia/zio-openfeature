package zio.openfeature

import zio.test._

/** `FlagType.decode(null)` must return a typed `Left`, never throw.
  *
  * `Int`, `Long`, `Double`, `Float`, `List[A]` and `Map[String, Any]` used to fall through to a fallback branch that
  * builds its message from `value.getClass.getSimpleName` — which raises an NPE on `null`, i.e. a defect where the
  * contract promises a `Left`. (`Boolean` and `String` already had an explicit null case.)
  *
  * Reachable from any provider that returns a null value, and from `FlagType.derived`'s absent-key handling. This lives
  * in the SHARED test dir on purpose: the fix was applied to both cross-build twins, so the guard has to run on both —
  * braces only, no `given`/`using`, no `enum`.
  */
object FlagTypeNullDecodeSpec extends ZIOSpecDefault {

  def spec = suite("FlagTypeNullDecodeSpec")(
    test("every built-in scalar instance decodes null to a Left rather than throwing") {
      assertTrue(
        FlagType[Boolean].decode(null).isLeft,
        FlagType[String].decode(null).isLeft,
        FlagType[Int].decode(null).isLeft,
        FlagType[Long].decode(null).isLeft,
        FlagType[Double].decode(null).isLeft,
        FlagType[Float].decode(null).isLeft
      )
    },
    test("the container instances decode null without throwing") {
      assertTrue(
        FlagType[List[String]].decode(null).isLeft,
        FlagType[Map[String, Any]].decode(null).isLeft,
        // Option is the deliberate exception: an absent value IS a domain value for it, so this is a Right(None).
        // That behaviour predates the fix and is what `derived` relies on for an absent Option field.
        FlagType[Option[String]].decode(null) == Right(None)
      )
    },
    test("the null message names the target type, so a failure is diagnosable") {
      assertTrue(
        FlagType[Int].decode(null).left.toOption.exists(_.contains("Int")),
        FlagType[Long].decode(null).left.toOption.exists(_.contains("Long")),
        FlagType[Double].decode(null).left.toOption.exists(_.contains("Double")),
        FlagType[Float].decode(null).left.toOption.exists(_.contains("Float")),
        FlagType[List[String]].decode(null).left.toOption.exists(_.contains("List")),
        FlagType[Map[String, Any]].decode(null).left.toOption.exists(_.contains("Object"))
      )
    },
    test("a null element inside a list is rejected rather than silently dropped") {
      // Arity matters: dropping the bad element would turn List(1, null) into List(1) — data loss with no error.
      val decoded = FlagType[List[Int]].decode(List(1, null))
      assertTrue(decoded.isLeft)
    },
    test("a null inside a List[Option[A]] decodes to None, preserving arity") {
      assertTrue(FlagType[List[Option[String]]].decode(List("a", null)) == Right(List(Some("a"), None)))
    }
  )
}
