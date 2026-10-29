package zio.openfeature.testsupport

import zio.test.TestAspect

/** The sample budget of a property test, shared by every module's property specs.
  *
  * A PR run uses each spec's base count. The nightly `Property tests` workflow multiplies it with `ZOF_PBT_SCALE` and
  * sets a fresh `ZOF_PBT_SEED`, which it prints, so a failure there replays locally with the same two variables. Every
  * test using this aspect is tagged `property`, which is how the nightly run selects them.
  */
object PropertyBudget {

  val Tag: String = "property"

  /** How many times the base sample count to run; 1 unless `ZOF_PBT_SCALE` is a positive integer. */
  val scale: Int =
    sys.env.get("ZOF_PBT_SCALE").flatMap(s => scala.util.Try(s.trim.toInt).toOption).filter(_ > 0).getOrElse(1)

  /** The random seed to run with, when `ZOF_PBT_SEED` is set; otherwise zio-test's fixed default. */
  val seed: Option[Long] = sys.env.get("ZOF_PBT_SEED").flatMap(s => scala.util.Try(s.trim.toLong).toOption)

  def apply(baseSamples: Int): TestAspect[Nothing, Any, Nothing, Any] =
    seed.foldLeft(TestAspect.samples(baseSamples * scale) >>> TestAspect.tag(Tag))((aspect, s) =>
      aspect >>> TestAspect.setSeed(s)
    )

  /** A time bound that grows with the sample budget, for a property test that bounds its whole run. */
  def timeout(base: zio.Duration): TestAspect[Nothing, Any, Nothing, Any] =
    TestAspect.timeout(base.multipliedBy(scale.toLong))
}
