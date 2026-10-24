package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.openfeature.testkit.FlagOverride.Ops // brings `:=` into scope
import zio.test.*

/** The Testkit page's code. Every documented test here is a real test: the page includes these sections, and this spec
  * runs them.
  */
object TestkitSpec extends ZIOSpecDefault:

  // --8<-- [start:service]
  // The code under test: a greeting gated by a flag
  trait Greeter:
    def greet(user: String): IO[FeatureFlagError, String]

  object Greeter:
    val live: URLayer[FeatureFlags, Greeter] =
      ZLayer.fromFunction((flags: FeatureFlags) =>
        new Greeter:
          def greet(user: String): IO[FeatureFlagError, String] =
            flags
              .boolean("new-greeting", default = false, EvaluationContext(user))
              .map(on => if on then s"Hey $user!" else s"Welcome, $user.")
      )
  // --8<-- [end:service]

  // --8<-- [start:first-test]
  val greeterSuite = suite("Greeter")(
    test("old greeting when the flag is off") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlag("new-greeting", false)
        greeting <- ZIO.serviceWithZIO[Greeter](_.greet("alice"))
      yield assertTrue(greeting == "Welcome, alice.")
    },
    test("new greeting when the flag is on") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlag("new-greeting", true)
        greeting <- ZIO.serviceWithZIO[Greeter](_.greet("bob"))
      yield assertTrue(greeting == "Hey bob!")
    }
  ).provide(TestFeatureProvider.layer, Greeter.live)
  // --8<-- [end:first-test]

  // --8<-- [start:typed-fixture]
  enum Tier derives FlagType:
    case Free, Premium

  object Flags:
    val NewGreeting = FlagDef("new-greeting", false)
    val Plan        = FlagDef[Tier]("user-plan", Tier.Free) // name the type: see the note below

  val typedFixture = test("typed fixture") {
    for plan <- FeatureFlags.value(Flags.Plan)
    yield assertTrue(plan == Tier.Premium)
  }.provide(TestFeatureProvider.layer(Flags.Plan := Tier.Premium, Flags.NewGreeting := true))
  // Flags.Plan := "premium" does not compile
  // --8<-- [end:typed-fixture]

  // --8<-- [start:managing-flags]
  val managingFlags = test("changing flags during a test") {
    for
      provider <- ZIO.service[TestFeatureProvider]
      _        <- provider.setFlags(Map("a" -> true, "b" -> "blue")) // merges into what is there
      _        <- provider.setFlag(Flags.Plan, Tier.Premium)         // typed, by definition
      _        <- provider.removeFlag("b")                           // "b" is now absent
      _        <- provider.replaceFlags(Map("c" -> 3))               // drops everything else first
      c        <- FeatureFlags.int("c", 0)
      a        <- FeatureFlags.booleanOrDefault("a", default = false)
    yield assertTrue(c == 3, !a)
  }.provide(TestFeatureProvider.layer(Map("a" -> false)))
  // --8<-- [end:managing-flags]

  // --8<-- [start:absent-keys]
  val absentKeys = test("an unset key is FLAG_NOT_FOUND") {
    for
      typed <- FeatureFlags.boolean("never-set", default = false).either
      total <- FeatureFlags.resolveOrDefault[Boolean]("never-set", default = false)
    yield assertTrue(
      typed == Left(FeatureFlagError.FlagNotFound("never-set")), // the typed tier fails
      total.value == false,                                      // the total tier serves the default...
      total.reason == ResolutionReason.Error,                    // ...and says why
      total.errorCode.contains(ErrorCode.FlagNotFound)
    )
  }.provide(TestFeatureProvider.layer)
  // --8<-- [end:absent-keys]

  // --8<-- [start:wrong-type]
  val wrongType = test("a value of the wrong type is a TYPE_MISMATCH, not the default") {
    for
      typed <- FeatureFlags.boolean("flag", default = false).either
      total <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
    yield assertTrue(
      typed.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]),
      total.errorCode.contains(ErrorCode.TypeMismatch),
      total.value == false
    )
  }.provide(TestFeatureProvider.layer(Map("flag" -> "not a boolean")))
  // --8<-- [end:wrong-type]

  // --8<-- [start:recorded]
  val recorded = test("which flags were read, how often, with what context") {
    for
      provider <- ZIO.service[TestFeatureProvider]
      _        <- ZIO.serviceWithZIO[Greeter](_.greet("alice"))
      _        <- ZIO.serviceWithZIO[Greeter](_.greet("bob"))
      read     <- provider.wasEvaluated(Flags.NewGreeting)
      count    <- provider.evaluationCount("new-greeting")
      evals    <- provider.getEvaluations // List[(String, EvaluationContext)]
      _        <- provider.clearEvaluations
      after    <- provider.evaluationCount("new-greeting")
    yield assertTrue(
      read,
      count == 2,
      evals.map(_._2.targetingKey) == List(Some("alice"), Some("bob")),
      after == 0
    )
  }.provide(TestFeatureProvider.layer(Map("new-greeting" -> true)), Greeter.live)
  // --8<-- [end:recorded]

  // --8<-- [start:status]
  val notReady = test("fails fast while the provider is not ready") {
    for
      provider <- ZIO.service[TestFeatureProvider]
      _        <- provider.setStatus(ProviderStatus.NotReady)
      down     <- ZIO.serviceWithZIO[Greeter](_.greet("carol")).either
      _        <- provider.setStatus(ProviderStatus.Ready)
      up       <- ZIO.serviceWithZIO[Greeter](_.greet("carol"))
    yield assertTrue(
      down == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
      up == "Hey carol!"
    )
  }.provide(TestFeatureProvider.layer(Map("new-greeting" -> true)), Greeter.live)
  // --8<-- [end:status]

  // --8<-- [start:async-layer]
  val asyncInit = test("a provider that is still starting up") {
    for
      provider <- ZIO.service[TestFeatureProvider]
      before   <- FeatureFlags.boolean("feature", default = false).either
      _        <- provider.setStatus(ProviderStatus.Ready) // simulate the provider finishing init
      after    <- FeatureFlags.boolean("feature", default = false)
    yield assertTrue(before.isLeft, after)
  }.provide(TestFeatureProvider.asyncLayer(Map("feature" -> true)))
  // --8<-- [end:async-layer]

  // --8<-- [start:async-ready-layer]
  val asyncReady = test("a provider that becomes ready on its own") {
    for
      _      <- TestClock.adjust(100.millis) // the layer's delay runs on the test clock
      status <- FeatureFlags.awaitReady()
      on     <- FeatureFlags.boolean("feature", default = false)
    yield assertTrue(status == ProviderStatus.Ready, on)
  }.provide(TestFeatureProvider.asyncReadyLayer(Map("feature" -> true), initDelay = 100.millis))
  // --8<-- [end:async-ready-layer]

  // --8<-- [start:events]
  val events = test("emit a provider event") {
    for
      provider <- ZIO.service[TestFeatureProvider]
      changed  <- Promise.make[Nothing, Set[String]]
      _        <- FeatureFlags.onConfigurationChanged((keys, _) => changed.succeed(keys).unit)
      _        <- provider.emitEvent(ProviderEvent.ConfigurationChanged(Set("banner"), provider.metadata))
      keys     <- changed.await
    yield assertTrue(keys == Set("banner"))
  }.provide(TestFeatureProvider.layer)
  // --8<-- [end:events]

  // --8<-- [start:failures]
  val failures = suite("when the provider misbehaves")(
    test("every evaluation fails with a chosen error") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setErrorMode(TestFeatureProvider.ErrorMode.ParseError)
        typed    <- FeatureFlags.boolean("flag", default = false).either
        total    <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = false)
      yield assertTrue(typed.isLeft, total.errorCode.contains(ErrorCode.ParseError))
    },
    test("the same, as an aspect that resets after the test") {
      for resolution <- FeatureFlags.resolveOrDefault[Boolean]("flag", default = true)
      yield assertTrue(resolution.value, resolution.errorCode.contains(ErrorCode.General))
    } @@ TestFeatureProvider.withFailures
  ).provide(TestFeatureProvider.layer(Map("flag" -> false))) @@ TestAspect.sequential
  // --8<-- [end:failures]

  // --8<-- [start:transactions]
  val withOverrides = test("override flags for one block") {
    for
      base <- FeatureFlags.boolean("new-greeting", default = false)
      tx <- FeatureFlags.transaction(overrides = Map("new-greeting" -> true)) {
        FeatureFlags.boolean("new-greeting", default = false)
      }
    yield assertTrue(!base, tx.result)
  }.provide(TestFeatureProvider.layer(Map("new-greeting" -> false)))
  // --8<-- [end:transactions]

  // --8<-- [start:chain]
  val chain = test("a test provider in a provider chain") {
    for
      primary  <- TestFeatureProvider.makeNamed("primary") // has no "flag"
      fallback <- TestFeatureProvider.makeNamed("fallback", Map("flag" -> true))
      chained = FeatureFlags.multiProvider(List(primary, fallback))
      value <- FeatureFlags
        .boolean("flag", default = false)
        .provide(FeatureFlags.fromProvider(chained))
      asked <- primary.wasEvaluated("flag")
    yield assertTrue(value, asked) // the primary was asked, said FLAG_NOT_FOUND, and the chain moved on
  }
  // --8<-- [end:chain]

  // --8<-- [start:laws]
  final case class Celsius(degrees: Int)
  object Celsius:
    given FlagType[Celsius] = FlagType.mapped[Celsius, Int]("Celsius", Celsius(0))(Celsius(_), _.degrees)

  val celsiusLaws = FlagTypeLaws.all(Gen.int(-100, 100).map(Celsius(_)))
  // --8<-- [end:laws]

  // --8<-- [start:caching-reason]
  val cachingReason = test("CachingReasonProvider reports CACHED on a repeat") {
    for
      provider <- TestFeatureProvider.make(Map("flag" -> true))
      layer = FeatureFlags.fromProvider(new CachingReasonProvider(provider))
      reasons <- (FeatureFlags.booleanDetails("flag", false) *> FeatureFlags.booleanDetails("flag", false))
        .map(_.reason)
        .provide(layer)
    yield assertTrue(reasons == ResolutionReason.Cached)
  }
  // --8<-- [end:caching-reason]

  def spec = suite("Testkit page")(
    greeterSuite,
    typedFixture,
    managingFlags,
    absentKeys,
    wrongType,
    recorded,
    notReady,
    asyncInit,
    asyncReady,
    events,
    failures,
    withOverrides,
    chain,
    celsiusLaws,
    cachingReason
  ) @@ TestAspect.sequential // `chain` and `cachingReason` use the global OpenFeature API
