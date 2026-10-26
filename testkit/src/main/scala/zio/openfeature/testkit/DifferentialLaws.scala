package zio.openfeature.testkit

import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}
import zio._
import zio.openfeature._
import zio.test._

/** A differential check: evaluate the same generated flags through [[TestFeatureProvider]] and through a real provider
  * (the '''oracle'''), and fail on every difference that is not listed as a known [[DifferentialLaws.Deviation]].
  *
  * The testkit is only useful if it answers the way a real provider does. A test provider that is looser than
  * production (serving a `Long` through the int getter, stringifying a number for a string read) lets a test pass where
  * production fails. This check keeps the two honest against each other:
  *
  *   - It generates random flag sets over every wire type (booleans, hostile strings, `Int`s, `Long`s at and beyond the
  *     `Int` range, hostile doubles, nested objects and lists) and, for each, a read of every key, plus one absent key,
  *     through every typed accessor, so mismatched types and missing keys are always covered. A fixed
  *     [[DifferentialLaws.corpus corpus]] of edge cases is read in every run on top of the random samples.
  *   - Both providers are evaluated '''through the same `FeatureFlags` layer''', so what is compared is what a user
  *     sees: the value, the [[DifferentialLaws.ReasonClass reason class]] and the error code. The reason class rather
  *     than the exact reason, because providers legitimately differ on `STATIC` vs `TARGETING_MATCH`.
  *   - Every disagreement must match a [[DifferentialLaws.Deviation]] that says why it is intended, or the check fails.
  *   - A deviation that matched '''no''' disagreement in the run fails the check as stale, so the list cannot outlive
  *     the behaviour it excuses.
  *
  * {{{
  * object HoconDifferentialSpec extends ZIOSpecDefault {
  *   def spec = suite("HoconProvider vs testkit")(
  *     DifferentialLaws.agreesWithTestkit("hocon")(
  *       seed = flags => ZIO.succeed(HoconProvider.fromConfig(ConfigFactory.parseString(render(flags)))),
  *       deviations = List(...)
  *     )
  *   )
  * }
  * }}}
  *
  * Sampling uses the test's `Random`, which zio-test seeds deterministically, so a run is reproducible. Use [[compare]]
  * instead of [[agreesWithTestkit]] to get the [[DifferentialLaws.Report]] itself.
  *
  * Shared source tree: works on Scala 2.13 and 3.
  */
object DifferentialLaws {

  /** The flags both providers hold. Values are plain Scala values, as `TestFeatureProvider.make` takes them. */
  final case class FlagSet(flags: Map[String, Any])

  /** Which typed accessor a [[Request]] goes through, with the default it passes. */
  sealed trait Accessor extends Product with Serializable {
    def default: Any
  }
  object Accessor {
    final case class BooleanRead(default: Boolean)         extends Accessor
    final case class StringRead(default: String)           extends Accessor
    final case class IntRead(default: Int)                 extends Accessor
    final case class LongRead(default: Long)               extends Accessor
    final case class DoubleRead(default: Double)           extends Accessor
    final case class ObjectRead(default: Map[String, Any]) extends Accessor
  }

  /** One evaluation: a key (possibly absent from the [[FlagSet]]) read through one accessor. */
  final case class Request(key: String, accessor: Accessor)

  /** What kind of answer an evaluation gave, ignoring provider-specific reason spellings. */
  sealed trait ReasonClass extends Product with Serializable
  object ReasonClass {

    /** A value the provider served: `STATIC`, `TARGETING_MATCH`, `SPLIT`, `CACHED` and the like. */
    case object Served extends ReasonClass

    /** The provider answered with reason `DEFAULT` and no error. */
    case object Default extends ReasonClass

    /** The evaluation failed or carried an error code; the value is the caller's default. */
    case object Error extends ReasonClass
  }

  /** What one provider answered for one [[Request]], as seen through `FeatureFlags`. */
  final case class Observed(value: Any, reasonClass: ReasonClass, errorCode: Option[ErrorCode])

  /** A known, intended difference between the testkit and one oracle.
    *
    * @param oracle
    *   the oracle name this deviation belongs to; it must equal the name given to [[compare]]
    * @param matches
    *   `(flags, request, testkit, oracle) => Boolean`: does this deviation explain the disagreement?
    * @param reason
    *   why the difference is intended (required, and shown in failure output)
    * @param issue
    *   the issue tracking the difference, if any
    */
  final case class Deviation(
    oracle: String,
    matches: (FlagSet, Request, Observed, Observed) => Boolean,
    reason: String,
    issue: Option[String] = None
  ) {
    override def toString: String = s"Deviation($oracle: $reason${issue.fold("")(i => s" ($i)")})"
  }

  /** One request on which the two providers disagreed. */
  final case class Disagreement(flags: FlagSet, request: Request, testkit: Observed, oracle: Observed) {
    def describe: String =
      s"${request.accessor} of '${request.key}' (holding ${flags.flags.get(request.key).fold("nothing")(show)}): " +
        s"testkit ${showObserved(testkit)}, oracle ${showObserved(oracle)}"
  }

  /** The outcome of a [[compare]] run.
    *
    * @param cases
    *   how many requests were evaluated through both providers
    * @param explained
    *   for every deviation for this oracle, how many disagreements it matched
    * @param unexplained
    *   disagreements no deviation matched
    * @param misfiled
    *   deviations whose `oracle` is not this oracle's name
    */
  final case class Report(
    oracle: String,
    cases: Int,
    explained: List[(Deviation, Int)],
    unexplained: List[Disagreement],
    misfiled: List[Deviation]
  ) {

    /** Deviations that matched nothing in this run. */
    def stale: List[Deviation] = explained.collect { case (d, 0) => d }

    def passed: Boolean = unexplained.isEmpty && stale.isEmpty && misfiled.isEmpty

    /** One line per problem, empty when the run passed. */
    def problems: List[String] =
      unexplained.take(MaxShown).map(d => s"unexplained: ${d.describe}") ++
        (if (unexplained.size > MaxShown) List(s"... and ${unexplained.size - MaxShown} more unexplained") else Nil) ++
        stale.map(d => s"stale (matched nothing in $cases cases): $d") ++
        misfiled.map(d => s"misfiled (belongs to '${d.oracle}', not '$oracle'): $d")
  }

  private val MaxShown = 10

  /** The key no generated flag set holds, read in every sample. */
  val AbsentKey: String = "absent-flag"

  // Generators

  private val hostileStrings: List[String] = List(
    "",
    " ",
    "\u0000",
    "a\u0000b",
    "\u0001\u001f\u007f",
    "\r\n\t",
    "é",
    "é",
    "日本語",
    "😀",
    "\\\"'",
    "null",
    "%00",
    "${x}"
  )

  /** Text a lenient provider might read as another type. */
  private val typedLookingStrings: List[String] =
    List("true", "false", "yes", "off", "42", "-7", "9999999999", "1.5", "1e3", "NaN", "Infinity")

  /** Strings worth reading: empty, control characters, combining marks, emoji, quotes, a substitution, and text that
    * looks like a number or a boolean.
    */
  val hostileString: Gen[Any, String] =
    Gen.oneOf(
      Gen.elements(hostileStrings: _*),
      Gen.elements(typedLookingStrings: _*),
      Gen.stringBounded(0, 12)(Gen.unicodeChar),
      Gen.alphaNumericString
    )

  private val negativeZero: Double = java.lang.Double.longBitsToDouble(0x8000000000000000L)

  private val specialDoubles: List[Double] = List(
    Double.NaN,
    Double.PositiveInfinity,
    Double.NegativeInfinity,
    negativeZero,
    0.0,
    2.0,
    -1.5,
    Double.MinValue,
    Double.MaxValue,
    java.lang.Double.MIN_VALUE,
    9007199254740993.0,
    1e19
  )

  /** Doubles including NaN, the infinities, -0.0, subnormals, whole numbers and values past 2^53. */
  val hostileDouble: Gen[Any, Double] =
    Gen.oneOf(Gen.elements(specialDoubles: _*), Gen.double(-1e6, 1e6), Gen.double(-1e300, 1e300))

  private val boundaryInts: List[Int] = List(Int.MinValue, Int.MaxValue, 0, -1, 1)

  val anyInt: Gen[Any, Int] = Gen.oneOf(Gen.elements(boundaryInts: _*), Gen.int(-1000, 1000), Gen.int)

  private val boundaryLongs: List[Long] = List(
    Long.MinValue,
    Long.MaxValue,
    Int.MinValue.toLong - 1,
    Int.MaxValue.toLong + 1,
    (1L << 53) + 1,
    0L,
    -1L
  )

  /** Longs both inside the `Int` range (still `Long`-typed) and beyond it, including past 2^53. */
  val anyLong: Gen[Any, Long] = Gen.oneOf(Gen.elements(boundaryLongs: _*), Gen.long(-1000L, 1000L), Gen.long)

  private def scalar: Gen[Any, Any] =
    Gen.oneOf(Gen.boolean, hostileString, anyInt, anyLong, hostileDouble)

  /** A nested value: scalars, `null`s, lists and maps of them, up to `depth` levels. */
  def nested(depth: Int): Gen[Any, Any] =
    if (depth <= 0) Gen.oneOf(scalar, Gen.const(null))
    else
      Gen.oneOf(
        scalar,
        Gen.listOfBounded(0, 3)(nested(depth - 1)),
        Gen.mapOfBounded(0, 3)(hostileString, nested(depth - 1))
      )

  /** An object flag value: a map of nested values. */
  val objectValue: Gen[Any, Map[String, Any]] = Gen.mapOfBounded(0, 4)(hostileString, nested(2))

  /** Flag keys: `flag-` and up to six lowercase letters or digits. Safe as a HOCON path and as an env var suffix, and
    * never equal to [[AbsentKey]].
    */
  val flagKey: Gen[Any, String] =
    Gen.stringBounded(1, 6)(Gen.oneOf(Gen.char('a', 'z'), Gen.char('0', '9'))).map("flag-" + _)

  /** A value of any wire type: boolean, string, int, long, double, object, or a top-level list. */
  val anyValue: Gen[Any, Any] =
    Gen.oneOf(Gen.boolean, hostileString, anyInt, anyLong, hostileDouble, objectValue, Gen.listOfBounded(0, 3)(scalar))

  /** Flag sets over every wire type. The default for [[agreesWithTestkit]]. */
  val flagSets: Gen[Any, FlagSet] = Gen.mapOfBounded(1, 6)(flagKey, anyValue).map(FlagSet(_))

  /** Flag sets of booleans, strings, ints and doubles only, for an oracle that cannot hold the other types (an
    * environment variable holds text).
    */
  val scalarFlagSets: Gen[Any, FlagSet] =
    Gen.mapOfBounded(1, 6)(flagKey, Gen.oneOf(Gen.boolean, hostileString, anyInt, hostileDouble)).map(FlagSet(_))

  private def keyed(values: List[Any]): FlagSet =
    FlagSet(values.zipWithIndex.map { case (v, i) => s"flag-c$i" -> v }.toMap)

  private val scalarCorpusValues: List[Any] =
    List[Any](true, false) ++ hostileStrings ++ typedLookingStrings ++ boundaryInts ++ specialDoubles

  /** One flag set holding every hand-picked value: each hostile and typed-looking string, the `Int` and `Long`
    * boundaries, every special double, and objects and lists containing them.
    *
    * It is evaluated in every run on top of the random samples. Random sampling finds the unexpected; the corpus makes
    * sure the known edge cases are read every time, so a deviation that exists for one of them (`NaN`, `-0.0`) does not
    * go stale just because a run happened not to generate it.
    */
  val corpus: FlagSet = keyed(
    scalarCorpusValues ++ boundaryLongs ++ List[Any](
      specialDoubles.zipWithIndex.map { case (d, i) => s"d$i" -> d }.toMap,
      Map(
        "nested" -> Map("list" -> List[Any](1, 2L, -0.0, Double.NaN, "x", true, null)),
        "empty"  -> Map.empty[String, Any]
      ),
      Map.empty[String, Any],
      List[Any](1, "two", 3.0),
      Nil
    )
  )

  /** The [[corpus]] restricted to booleans, strings, ints and doubles, to go with [[scalarFlagSets]]. */
  val scalarCorpus: FlagSet = keyed(scalarCorpusValues)

  /** The requests for one flag set: every key and [[AbsentKey]], each through every accessor, with generated defaults.
    */
  def requestsFor(flags: FlagSet): Gen[Any, List[Request]] =
    for {
      b <- Gen.boolean
      s <- hostileString
      i <- anyInt
      l <- anyLong
      d <- hostileDouble
      o <- Gen.mapOfBounded(0, 2)(Gen.alphaNumericStringBounded(1, 4), Gen.alphaNumericString)
    } yield {
      val accessors = List[Accessor](
        Accessor.BooleanRead(b),
        Accessor.StringRead(s),
        Accessor.IntRead(i),
        Accessor.LongRead(l),
        Accessor.DoubleRead(d),
        Accessor.ObjectRead(o)
      )
      for {
        key <- flags.flags.keys.toList.sorted :+ AbsentKey
        acc <- accessors
      } yield Request(key, acc)
    }

  // Running

  /** Run the comparison and return the report, without asserting anything. [[agreesWithTestkit]] is this plus an
    * assertion that the report passed.
    *
    * @param oracleName
    *   the name the deviations are filed under
    * @param seed
    *   builds the oracle holding the given flags; the provider lives until the scope closes
    * @param deviations
    *   the known differences; each must match at least one disagreement
    * @param samples
    *   how many random flag sets to generate
    * @param flagSets
    *   the flag-set generator; narrow it for an oracle that cannot represent every wire type
    * @param corpus
    *   a fixed flag set evaluated in every run besides the samples; narrow it along with `flagSets`
    */
  def compare(oracleName: String)(
    seed: FlagSet => URIO[Scope, FeatureProvider],
    deviations: List[Deviation],
    samples: Int = 50,
    flagSets: Gen[Any, FlagSet] = DifferentialLaws.flagSets,
    corpus: FlagSet = DifferentialLaws.corpus
  ): Task[Report] = {
    val (mine, misfiled) = deviations.partition(_.oracle == oracleName)
    flagSets
      .runCollectN(samples)
      .flatMap(random =>
        ZIO.foreach(corpus :: random)(fs => requestsFor(fs).runHead.map(rs => (fs, rs.getOrElse(Nil))))
      )
      .flatMap(ZIO.foreach(_) { case (fs, requests) => runSample(fs, requests, seed) })
      .map { perSample =>
        val all           = perSample.flatten
        val disagreements = all.collect { case Right(d) => d }
        val explained     = mine.map(dev => dev -> disagreements.count(d => explains(dev, d)))
        val unexplained   = disagreements.filterNot(d => mine.exists(explains(_, d)))
        Report(oracleName, all.size, explained, unexplained, misfiled)
      }
  }

  /** A test that fails on any unexplained disagreement, any stale deviation and any misfiled one. See [[compare]] for
    * the parameters.
    */
  def agreesWithTestkit(oracleName: String)(
    seed: FlagSet => URIO[Scope, FeatureProvider],
    deviations: List[Deviation],
    samples: Int = 50,
    flagSets: Gen[Any, FlagSet] = DifferentialLaws.flagSets,
    corpus: FlagSet = DifferentialLaws.corpus
  ): Spec[Any, Throwable] =
    zio.test.test(s"$oracleName agrees with TestFeatureProvider, apart from ${deviations.size} listed deviation(s)") {
      compare(oracleName)(seed, deviations, samples, flagSets, corpus).map { report =>
        val problems = report.problems
        assertTrue(problems.isEmpty)
      }
    }

  private def explains(dev: Deviation, d: Disagreement): Boolean =
    dev.matches(d.flags, d.request, d.testkit, d.oracle)

  /** Left = an agreeing case (kept only to be counted), Right = a disagreement. */
  private def runSample(
    fs: FlagSet,
    requests: List[Request],
    seed: FlagSet => URIO[Scope, FeatureProvider]
  ): Task[List[Either[Unit, Disagreement]]] =
    ZIO.scoped {
      for {
        testProvider <- TestFeatureProvider.make(fs.flags)
        testFlags    <- flagsFor(testProvider)
        oracle       <- seed(fs)
        oracleFlags  <- flagsFor(oracle)
        results      <- ZIO.foreach(requests) { r =>
          observe(testFlags, r).zip(observe(oracleFlags, r)).map { case (t, o) =>
            if (same(t, o)) Left(()) else Right(Disagreement(fs, r, t, o))
          }
        }
      } yield results
    }

  private def flagsFor(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .fromProvider(
        provider,
        FeatureFlagsConfig(),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
      .map(_.get[FeatureFlags])

  /** Evaluate through the partial (`*Details`) tier and fold a failure into what the total tier would serve: the
    * default, reason class `Error`, and the error code. A defect is `GENERAL`, as the total tier reports it.
    */
  private def observe(flags: FeatureFlags, r: Request): UIO[Observed] = {
    val details: IO[FeatureFlagError, FlagResolution[Any]] = r.accessor match {
      case Accessor.BooleanRead(d) => flags.booleanDetails(r.key, d)
      case Accessor.StringRead(d)  => flags.stringDetails(r.key, d)
      case Accessor.IntRead(d)     => flags.intDetails(r.key, d)
      case Accessor.LongRead(d)    => flags.longDetails(r.key, d)
      case Accessor.DoubleRead(d)  => flags.doubleDetails(r.key, d)
      case Accessor.ObjectRead(d)  => flags.objDetails(r.key, d)
    }
    details.foldCauseZIO(
      cause =>
        if (cause.isInterruptedOnly) ZIO.refailCause(cause.stripFailures)
        else {
          val code = cause.failureOption.fold[ErrorCode](ErrorCode.General)(FeatureFlagError.toErrorCode)
          ZIO.succeed(Observed(r.accessor.default, ReasonClass.Error, Some(code)))
        },
      res => ZIO.succeed(Observed(res.value, reasonClass(res), res.errorCode))
    )
  }

  private def reasonClass(res: FlagResolution[Any]): ReasonClass =
    if (res.isError) ReasonClass.Error
    else if (res.reason == ResolutionReason.Default) ReasonClass.Default
    else ReasonClass.Served

  private def same(a: Observed, b: Observed): Boolean =
    a.reasonClass == b.reasonClass && a.errorCode == b.errorCode && sameValue(a.value, b.value)

  /** The value equality the comparison uses, for writing [[Deviation]]s: strict about runtime type (`1` is not `1L`,
    * `-0.0` is not `0.0`), recursive through maps and lists, and `NaN` equals `NaN`.
    */
  def sameValue(a: Any, b: Any): Boolean = canonical(a) == canonical(b)

  /** Class-tagged form of a value, so `Int` vs `Long` and `-0.0` vs `0.0` stay distinct (Scala's cooperative equality
    * would treat `1 == 1L` as equal) while `NaN` equals `NaN`.
    */
  private def canonical(v: Any): Any = v match {
    case null         => null
    case d: Double    => ("Double", java.lang.Double.doubleToLongBits(d))
    case f: Float     => ("Float", java.lang.Float.floatToIntBits(f))
    case m: Map[_, _] => m.map { case (k, x) => k -> canonical(x) }
    case l: List[_]   => l.map(canonical)
    case other        => (other.getClass.getName, other)
  }

  private def showObserved(o: Observed): String =
    s"${o.reasonClass}${o.errorCode.fold("")(c => s" $c")} ${show(o.value)}"

  private def show(v: Any): String = v match {
    case null         => "null"
    case s: String    => "\"" + s.flatMap(c => if (c < ' ' || c > '~') f"\\u${c.toInt}%04x" else c.toString) + "\""
    case l: Long      => s"${l}L"
    case d: Double    => s"${d}d"
    case m: Map[_, _] => m.map { case (k, x) => s"${show(k)} -> ${show(x)}" }.mkString("Map(", ", ", ")")
    case l: List[_]   => l.map(show).mkString("List(", ", ", ")")
    case other        => other.toString
  }
}
