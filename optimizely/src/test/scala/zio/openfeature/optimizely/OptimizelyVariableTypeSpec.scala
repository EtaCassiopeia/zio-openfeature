package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ErrorCode, ImmutableContext, ProviderEvaluation, Reason, Value}
import zio._
import zio.test._

import scala.jdk.CollectionConverters._

// A variable that exists with another type is a TYPE_MISMATCH, not the caller's default with reason DEFAULT; an
// absent variable stays DEFAULT. Conversions are strict and lossless (no "42" -> 42, no 1.5 -> 1).
object OptimizelyVariableTypeSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/typed-variables-key.json"
  private val Flag         = "typed_flag"

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  private def withProvider[A](body: OptimizelyFeatureProvider => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try {
      server.stubFor(
        get(urlEqualTo(DatafilePath)).willReturn(okJson(readResource("/datafiles/typed-variables.json")))
      )
      val provider = new OptimizelyFeatureProvider(
        OptimizelyTestSupport.wiredClient(s"http://localhost:${server.port()}$DatafilePath", "typed-variables-key"),
        OptimizelyTestSupport.InitWait,
        closeOnShutdown = true
      )
      provider.initialize(new ImmutableContext())
      try body(provider)
      finally provider.shutdown()
    } finally server.stop()
  }

  private def variable(name: String): ImmutableContext =
    new ImmutableContext(
      "user-1",
      Map[String, Value](OptimizelyFeatureProvider.VariableKeyAttribute -> new Value(name)).asJava
    )

  // Every evaluation is bound to a val inside `withProvider`: `assertTrue` evaluates its argument lazily, after the
  // provider has been shut down.
  private def isMismatch[A](e: ProviderEvaluation[A], default: A): Boolean =
    e.getErrorCode == ErrorCode.TYPE_MISMATCH && e.getReason == Reason.ERROR.name && e.getValue == default

  private def matched[A](e: ProviderEvaluation[A], value: A): Boolean =
    e.getErrorCode == null && e.getReason == Reason.TARGETING_MATCH.name && e.getValue == value

  private val intDefault: java.lang.Integer = 99
  private val longDefault: java.lang.Long   = 99L
  private val dblDefault: java.lang.Double  = 9.9

  private def mismatch[A](eval: OptimizelyFeatureProvider => ProviderEvaluation[A], default: A) =
    withProvider { p =>
      val e = eval(p)
      assertTrue(isMismatch(e, default))
    }

  private def resolves[A](eval: OptimizelyFeatureProvider => ProviderEvaluation[A], value: A) =
    withProvider { p =>
      val e = eval(p)
      assertTrue(matched(e, value))
    }

  def spec = suite("OptimizelyVariableTypeSpec")(
    test("integer: a string variable is a TYPE_MISMATCH") {
      mismatch(_.getIntegerEvaluation(Flag, intDefault, variable("s")), intDefault)
    },
    test("integer: a numeric string is a TYPE_MISMATCH, not parsed") {
      mismatch(_.getIntegerEvaluation(Flag, intDefault, variable("n")), intDefault)
    },
    test("integer: a double variable is a TYPE_MISMATCH, not truncated") {
      mismatch(_.getIntegerEvaluation(Flag, intDefault, variable("d")), intDefault)
    },
    test("integer: an integer beyond Int range is a TYPE_MISMATCH, not wrapped") {
      mismatch(_.getIntegerEvaluation(Flag, intDefault, variable("big")), intDefault)
    },
    test("integer: an absent variable is the default with reason DEFAULT and no error") {
      withProvider { p =>
        val e = p.getIntegerEvaluation(Flag, intDefault, variable("missing"))
        assertTrue(e.getValue == intDefault, e.getReason == Reason.DEFAULT.name, e.getErrorCode == null)
      }
    },
    test("integer: an integer variable resolves") {
      resolves(_.getIntegerEvaluation(Flag, intDefault, variable("i")), Int.box(7))
    },
    test("long: an integer beyond Int range resolves exactly") {
      resolves(_.getLongEvaluation(Flag, longDefault, variable("big")), Long.box(5000000000L))
    },
    test("long: an integer variable widens") {
      resolves(_.getLongEvaluation(Flag, longDefault, variable("i")), Long.box(7L))
    },
    test("long: a string variable is a TYPE_MISMATCH") {
      mismatch(_.getLongEvaluation(Flag, longDefault, variable("s")), longDefault)
    },
    test("double: an integer variable widens") {
      resolves(_.getDoubleEvaluation(Flag, dblDefault, variable("i")), Double.box(7.0))
    },
    test("double: a double variable resolves") {
      resolves(_.getDoubleEvaluation(Flag, dblDefault, variable("d")), Double.box(1.5))
    },
    test("double: a string variable is a TYPE_MISMATCH") {
      mismatch(_.getDoubleEvaluation(Flag, dblDefault, variable("s")), dblDefault)
    },
    test("string: an integer variable is a TYPE_MISMATCH") {
      mismatch(_.getStringEvaluation(Flag, "dflt", variable("i")), "dflt")
    },
    test("string: a string variable resolves") {
      resolves(_.getStringEvaluation(Flag, "dflt", variable("s")), "abc")
    },
    test("string: an absent variable falls back to the variation key, unchanged") {
      resolves(_.getStringEvaluation(Flag, "dflt", variable("missing")), "on")
    },
    test("object: a string variable is a TYPE_MISMATCH") {
      val default = new Value("dflt")
      mismatch(_.getObjectEvaluation(Flag, default, variable("s")), default)
    },
    test("object: a json variable resolves") {
      withProvider { p =>
        val e = p.getObjectEvaluation(Flag, new Value("dflt"), variable("j"))
        assertTrue(e.getReason == Reason.TARGETING_MATCH.name, e.getValue.isStructure)
      }
    },
    test("through the core: the typed tier fails with TypeMismatch, the total tier serves the default") {
      withProvider { p =>
        val ctx =
          zio.openfeature.EvaluationContext("user-1").withAttribute(OptimizelyFeatureProvider.VariableKeyAttribute, "s")
        val program = for {
          typed <- zio.openfeature.FeatureFlags.int(Flag, 99, ctx).either
          total <- zio.openfeature.FeatureFlags.intOrDefault(Flag, 99, ctx)
        } yield (typed, total)
        val (typed, total) = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe
            .run(
              program.provide(
                zio.openfeature.FeatureFlags.fromProvider(
                  p,
                  zio.openfeature.FeatureFlagsConfig(),
                  statusRef = None,
                  apiOverride = Some(dev.openfeature.sdk.OpenFeatureAPI.createIsolated())
                )
              )
            )
            .getOrThrowFiberFailure()
        }
        assertTrue(
          typed.left.toOption.exists(_.isInstanceOf[zio.openfeature.FeatureFlagError.TypeMismatch]),
          total == 99
        )
      }
    },
    test("a TYPE_MISMATCH message names the variable and both types") {
      withProvider { p =>
        val msg = Option(p.getIntegerEvaluation(Flag, intDefault, variable("s")).getErrorMessage).getOrElse("")
        assertTrue(msg.contains("'s'"), msg.contains("String"), msg.contains("Integer"))
      }
    }
  ) @@ TestAspect.sequential
}
