package snippets

import com.typesafe.config.ConfigFactory
import dev.openfeature.sdk.{EvaluationContext as OFContext, FeatureProvider, Metadata, ProviderEvaluation, Value}
import dev.openfeature.sdk.exceptions.{GeneralError, TypeMismatchError}
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*
import zio.test.*
import zio.test.TestAspect.withLiveClock

import java.util.concurrent.atomic.AtomicInteger

/** Checks what the Extras page says each provider does. */
object ExtrasSpec extends ZIOSpecDefault:

  /** Answers every boolean with `true` (or throws, while `failing` is set) and counts its calls. */
  final class CountingProvider extends FeatureProvider:
    val calls                 = new AtomicInteger(0)
    @volatile var failing     = false
    def getMetadata: Metadata = () => "counting"
    private def answer[A](value: A): ProviderEvaluation[A] =
      calls.incrementAndGet()
      if failing then throw new GeneralError("remote is down")
      val b = ProviderEvaluation.builder[A]()
      b.value(value)
      b.reason("TARGETING_MATCH")
      b.build()
    def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFContext) = answer(java.lang.Boolean.TRUE)
    def getStringEvaluation(k: String, d: String, c: OFContext)             = answer("remote")
    def getIntegerEvaluation(k: String, d: Integer, c: OFContext)           = answer(Integer.valueOf(1))
    def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFContext)   = answer(java.lang.Double.valueOf(1))
    def getObjectEvaluation(k: String, d: Value, c: OFContext)              = answer(new Value("remote"))

  /** A pre-1.22 provider: it never overrode `getLongEvaluation`, and its double resolver rejects integer flags. */
  final class LegacyProvider extends FeatureProvider:
    def getMetadata: Metadata = () => "legacy"
    private def ok[A](value: A): ProviderEvaluation[A] =
      val b = ProviderEvaluation.builder[A]()
      b.value(value)
      b.reason("STATIC")
      b.build()
    def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFContext) = ok(d)
    def getStringEvaluation(k: String, d: String, c: OFContext)             = ok(d)
    def getIntegerEvaluation(k: String, d: Integer, c: OFContext)           = ok(Integer.valueOf(42))
    def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFContext) =
      throw new TypeMismatchError(s"'$k' is an integer flag")
    def getObjectEvaluation(k: String, d: Value, c: OFContext) = ok(d)

  private def hocon(text: String) = HoconProvider.fromConfig(ConfigFactory.parseString(text))

  // Each test registers its provider under its own domain: the OpenFeature API is process-global, and other specs in
  // this module run in the same JVM.
  private val domains = new AtomicInteger(0)

  private def flags[A](provider: FeatureProvider)(program: ZIO[FeatureFlags, Any, A]): ZIO[Any, Any, A] =
    val domain = s"extras-spec-${domains.incrementAndGet()}"
    ZIO.scoped(program.provideSome[Scope](FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain(domain))))

  def spec = suite("Extras")(
    suite("HoconProvider")(
      test("reads typed values with reason Static, and reports an absent key as not found") {
        flags(hocon("new-checkout = true\nmax-items = 50\nsettings { timeout = 30 }")) {
          for
            enabled  <- FeatureFlags.booleanDetails("new-checkout", default = false)
            items    <- FeatureFlags.int("max-items", default = 10)
            settings <- FeatureFlags.obj("settings", default = Map.empty)
            missing  <- FeatureFlags.boolean("absent", default = false).flip
            total    <- FeatureFlags.resolveOrDefault[Boolean]("absent", default = true)
          yield assertTrue(
            enabled.value,
            enabled.reason == ResolutionReason.Static,
            items == 50,
            settings.contains("timeout"),
            missing == FeatureFlagError.FlagNotFound("absent"),
            total.value,
            total.errorCode.contains(ErrorCode.FlagNotFound)
          )
        }
      },
      test("a value of the wrong type is a TypeMismatch") {
        flags(hocon("max-items = fifty")) {
          FeatureFlags
            .int("max-items", default = 10)
            .flip
            .map(e => assertTrue(e.isInstanceOf[FeatureFlagError.TypeMismatch]))
        }
      },
      test("the snippet's config-backed provider reads its value") {
        flags(Extras.fromConfig)(FeatureFlags.boolean("new-checkout", default = false).map(assertTrue(_)))
      }
    ),
    suite("EnvVarProvider")(
      test("maps keys to variable names, coerces values, and reports unset and unparsable variables") {
        val env = Map("FF_NEW_CHECKOUT" -> "yes", "FF_MAX_ITEMS" -> "abc", "FF_APP_FEATURE_ENABLED" -> "on")
        flags(EnvVarProvider.withLookup(env.get)) {
          for
            checkout <- FeatureFlags.boolean("new-checkout", default = false)
            dotted   <- FeatureFlags.boolean("app.feature.enabled", default = false)
            parse    <- FeatureFlags.int("max-items", default = 10).flip
            unset    <- FeatureFlags.boolean("other", default = false).flip
          yield assertTrue(
            checkout,
            dotted,
            parse.isInstanceOf[FeatureFlagError.ParseError],
            unset == FeatureFlagError.FlagNotFound("other")
          )
        }
      },
      test("prefix and key transform") {
        val env = Map("MYAPP_NEW_CHECKOUT" -> "true", "FF_A__B" -> "true")
        for
          prefixed <- flags(EnvVarProvider.withLookup(env.get, prefix = "MYAPP_"))(
            FeatureFlags.boolean("new-checkout", false)
          )
          custom <- flags(EnvVarProvider.withLookup(env.get, keyTransform = _.toUpperCase.replace(".", "__")))(
            FeatureFlags.boolean("a.b", false)
          )
        yield assertTrue(prefixed, custom)
      }
    ),
    test("a firstMatch chain: env vars override HOCON, and HOCON answers what env does not hold") {
      val env   = EnvVarProvider.withLookup(Map("FF_A" -> "false").get)
      val chain = FeatureFlags.multiProvider(List(env, hocon("a = true\nb = true")))
      flags(chain) {
        for
          a <- FeatureFlags.boolean("a", default = true)
          b <- FeatureFlags.boolean("b", default = false)
        yield assertTrue(!a, b)
      }
    },
    suite("CachingProvider")(
      test("a repeat evaluation is served from the cache with reason Cached") {
        val remote = new CountingProvider
        for
          cached <- CachingProvider.make(remote, CachingConfig(ttl = 1.minute))
          r      <- flags(cached)(FeatureFlags.booleanDetails("f", false) <*> FeatureFlags.booleanDetails("f", false))
        yield assertTrue(remote.calls.get == 1, r._1.reason == ResolutionReason.TargetingMatch, r._2.isCached)
      },
      test("failures are not cached") {
        val remote = new CountingProvider
        remote.failing = true
        for
          cached <- CachingProvider.make(remote)
          _ <- flags(cached)(FeatureFlags.booleanOrDefault("f", false) *> FeatureFlags.booleanOrDefault("f", false))
        yield assertTrue(remote.calls.get == 2)
      },
      test("contextKeys: users on the same plan share an entry") {
        val remote = new CountingProvider
        val ctx    = (user: String) => EvaluationContext(user).withAttribute("plan", "pro")
        for
          cached <- CachingProvider.make(remote, CachingConfig(contextKeys = Some(Set("plan"))))
          _ <- flags(cached)(
            FeatureFlags.boolean("f", false, ctx("ann")) *> FeatureFlags.boolean("f", false, ctx("bob"))
          )
        yield assertTrue(remote.calls.get == 1)
      },
      test("invalidateAll empties the cache") {
        val remote = new CountingProvider
        for
          cached <- CachingProvider.make(remote)
          _ <- flags(cached)(
            FeatureFlags.boolean("f", false) *> cached.invalidateAll *> FeatureFlags.boolean("f", false)
          )
        yield assertTrue(remote.calls.get == 2)
      }
    ),
    test("CircuitBreakerProvider: once open, the fallback answers and the delegate is not called") {
      val remote = new CountingProvider
      remote.failing = true
      for
        breaker <- CircuitBreakerProvider.make(remote, CircuitBreakerProviderConfig(failureThreshold = 2))
        chain = FeatureFlags.multiProvider(List(breaker, hocon("f = false")), MultiProviderStrategy.firstSuccessful)
        answers <- flags(chain)(ZIO.foreach(1 to 5)(_ => FeatureFlags.boolean("f", default = true)))
      yield assertTrue(answers.forall(_ == false), remote.calls.get == 2)
    } @@ withLiveClock,
    test("DeferredProvider builds the delegate on initialize, not before") {
      val built = new AtomicInteger(0)
      val deferred = DeferredProvider("hocon") { () =>
        built.incrementAndGet(); hocon("f = true")
      }
      for
        before <- ZIO.succeed(built.get)
        value  <- flags(deferred)(FeatureFlags.boolean("f", default = false))
      yield assertTrue(before == 0, built.get == 1, value)
    },
    test("IntegerWideningLongProvider resolves a Long through the provider's integer resolver") {
      for
        bare    <- flags(new LegacyProvider)(FeatureFlags.long("n", 0L)).flip
        widened <- flags(IntegerWideningLongProvider(new LegacyProvider))(FeatureFlags.long("n", 0L))
      yield assertTrue(bare.isInstanceOf[FeatureFlagError.TypeMismatch], widened == 42L)
    }
  ) @@ TestAspect.sequential
