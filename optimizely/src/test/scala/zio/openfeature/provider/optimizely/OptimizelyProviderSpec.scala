package zio.openfeature.provider.optimizely

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.openfeature.*
import com.optimizely.ab.Optimizely

object OptimizelyProviderSpec extends ZIOSpecDefault:

  val minimalDatafile = """{
    "version": "4",
    "rollouts": [],
    "typedAudiences": [],
    "anonymizeIP": true,
    "projectId": "test-project",
    "variables": [],
    "featureFlags": [],
    "experiments": [],
    "audiences": [],
    "groups": [],
    "attributes": [],
    "accountId": "test-account",
    "events": [],
    "revision": "1"
  }"""

  def spec = suite("OptimizelyProviderSpec")(
    suite("provider lifecycle")(
      test("starts with NotReady status") {
        for
          client   <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider <- OptimizelyProvider.make(client)
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.NotReady)
      },
      test("initialize sets Ready status") {
        for
          client   <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider <- OptimizelyProvider.make(client)
          _        <- provider.initialize
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.Ready)
      },
      test("shutdown sets NotReady status") {
        for
          client   <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider <- OptimizelyProvider.make(client)
          _        <- provider.initialize
          _        <- provider.shutdown
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.NotReady)
      },
      test("metadata is correct") {
        for
          client   <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider <- OptimizelyProvider.make(client)
        yield assertTrue(provider.metadata.name == "Optimizely") &&
          assertTrue(provider.metadata.version == Some("4.1.1"))
      }
    ),
    suite("flag resolution with empty datafile")(
      test("returns default for unknown boolean flag") {
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveBooleanValue("unknown-flag", true, EvaluationContext.empty)
        yield assertTrue(resolution.value == true) &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("returns default for unknown string flag") {
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveStringValue("unknown-flag", "default", EvaluationContext.empty)
        yield assertTrue(resolution.value == "default") &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("returns default for unknown int flag") {
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveIntValue("unknown-flag", 42, EvaluationContext.empty)
        yield assertTrue(resolution.value == 42) &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("returns default for unknown double flag") {
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveDoubleValue("unknown-flag", 3.14, EvaluationContext.empty)
        yield assertTrue(resolution.value == 3.14) &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("returns default for unknown object flag") {
        val defaultValue = Map("key" -> "value")
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveObjectValue("unknown-flag", defaultValue, EvaluationContext.empty)
        yield assertTrue(resolution.value == defaultValue) &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      }
    ),
    suite("context handling")(
      test("uses targeting key as user id") {
        val ctx = EvaluationContext("user-123")
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveBooleanValue("flag", false, ctx)
        yield assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("uses anonymous for empty context") {
        for
          client     <- ZIO.attempt(Optimizely.builder().withDatafile(minimalDatafile).build())
          provider   <- OptimizelyProvider.make(client)
          _          <- provider.initialize
          resolution <- provider.resolveBooleanValue("flag", false, EvaluationContext.empty)
        yield assertTrue(resolution.reason == ResolutionReason.Default)
      }
    ),
    suite("layer")(
      test("layer provides FeatureProvider") {
        val client = Optimizely.builder().withDatafile(minimalDatafile).build()
        val effect = for
          provider <- ZIO.service[FeatureProvider]
          _        <- provider.initialize
          result   <- provider.resolveBooleanValue("test", false, EvaluationContext.empty)
        yield assertTrue(result.reason == ResolutionReason.Default)

        effect.provide(OptimizelyProvider.layer(client))
      }
    )
  )
