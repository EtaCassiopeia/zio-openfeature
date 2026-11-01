package zio.openfeature.extras

import zio._
import zio.openfeature.ErrorCode
import zio.openfeature.testkit.DifferentialLaws
import zio.openfeature.testkit.DifferentialLaws._
import zio.test._

/** `EnvVarProvider` against `TestFeatureProvider`.
  *
  * An environment variable holds text, so this oracle runs on the boolean / string / int / double subset of the wire
  * types, each flag rendered with `toString`. The deviations are the consequences of holding text instead of a typed
  * value.
  */
object EnvVarDifferentialSpec extends ZIOSpecDefault {

  private val oracle = "env-var"

  def spec = suite("EnvVarDifferentialSpec")(
    DifferentialLaws.agreesWithTestkit(oracle)(
      seed = flags => ZIO.succeed(EnvVarProvider.withLookup(environment(flags).get)),
      deviations = deviations,
      flagSets = DifferentialLaws.scalarFlagSets,
      corpus = DifferentialLaws.scalarCorpus
    ),
    DifferentialLaws.absentKeysAreNotFound(oracle)(flags =>
      ZIO.succeed(EnvVarProvider.withLookup(environment(flags).get))
    )
  )

  // Deviations

  private def isTypedScalarRead(r: Request): Boolean = r.accessor match {
    case _: Accessor.BooleanRead | _: Accessor.IntRead | _: Accessor.LongRead | _: Accessor.DoubleRead => true
    case _                                                                                             => false
  }

  private def typeMismatch(testkit: Observed): Boolean = testkit.errorCode.contains(ErrorCode.TypeMismatch)

  val deviations: List[Deviation] = List(
    Deviation(
      oracle,
      (flags, request, testkit, env) =>
        request.accessor.isInstanceOf[Accessor.StringRead] && typeMismatch(testkit) &&
          env.reasonClass == ReasonClass.Served && flags.flags.get(request.key).exists(v => env.value == v.toString),
      "An environment variable is text: a string read of any flag serves its text."
    ),
    Deviation(
      oracle,
      (_, request, testkit, env) =>
        isTypedScalarRead(request) && typeMismatch(testkit) && env.reasonClass == ReasonClass.Served,
      "An environment variable is text: a typed read parses it, so a flag of another type is served when its text " +
        "parses (an int as a long or double, \"42\" as an int, 0 and 1 and \"false\" as booleans)."
    ),
    Deviation(
      oracle,
      (_, _, testkit, env) => typeMismatch(testkit) && env.errorCode.contains(ErrorCode.ParseError),
      "An environment variable is text: a typed read of text that does not parse is PARSE_ERROR, since the " +
        "provider cannot tell a wrongly typed flag from a malformed one."
    )
  )

  /** The environment that holds a flag set, under the provider's default prefix and key transform. */
  def environment(flags: FlagSet): Map[String, String] =
    flags.flags.map { case (k, v) => ("FF_" + EnvVarProvider.defaultKeyTransform(k)) -> v.toString }
}
