package zio.openfeature.optimizely.matrix

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import zio._
import zio.openfeature._
import zio.openfeature.optimizely.{OptimizelyProvider, OptimizelyProviderConfig}
import zio.test._

/** Standalone ZIO test that exercises the RecommendationService against each datafile fixture without going through the
  * zio-bdd harness.
  */
object RecommendationServiceSpec extends ZIOSpecDefault {

  private val SdkKey       = "test-matrix-key"
  private val DatafilePath = s"/datafiles/$SdkKey.json"

  private def loadDatafile(name: String): String = {
    val path = s"/datafiles/$name.json"
    val is   = getClass.getResourceAsStream(path)
    require(is != null, s"Datafile not found: $path")
    scala.io.Source.fromInputStream(is).mkString
  }

  private def withProvider[A](
    datafileName: String
  )(body: RecommendationService => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.scoped {
      for {
        server <- ZIO.acquireRelease(ZIO.succeed {
          val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
          s.start()
          s.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(loadDatafile(datafileName))))
          s
        })(s => ZIO.succeed(s.stop()))
        dataUrl = s"http://localhost:${server.port()}$DatafilePath"
        config = OptimizelyProviderConfig(
          sdkKey = SdkKey,
          datafileUrl = Some(dataUrl),
          initWait = java.time.Duration.ofSeconds(5),
          pollingInterval = Some(java.time.Duration.ofSeconds(3600)),
          blockingTimeout = Some(java.time.Duration.ofSeconds(2))
        )
        provider <- OptimizelyProvider.scoped(config).mapError(e => new RuntimeException(e.message))
        // fromProviderWithDomain (not the plain fromProvider) so each call gets its own named slot on
        // the global OpenFeatureAPI singleton instead of overwriting the shared unnamed default client —
        // fromProvider would let concurrently-running tests race to swap each other's active provider.
        domain = s"flag-matrix-${java.util.UUID.randomUUID()}"
        env <- FeatureFlags.fromProviderWithDomain(provider, domain).build
        ff  = env.get[FeatureFlags]
        svc = new RecommendationService(ff)
        result <- body(svc)
      } yield result
    }

  def spec = suite("RecommendationService")(
    suite("basic flag matrix")(
      test("empty datafile → default") {
        withProvider("empty") { svc =>
          svc.recommend.map(kind => assertTrue(kind == "default"))
        }
      },
      test("kill-switch-off → alpha") {
        withProvider("kill-switch-off") { svc =>
          svc.recommend.map(kind => assertTrue(kind == "alpha"))
        }
      },
      test("kill-switch-on → degraded") {
        withProvider("kill-switch-on") { svc =>
          svc.recommend.map(kind => assertTrue(kind == "degraded"))
        }
      },
      test("variant-beta → beta") {
        withProvider("variant-beta") { svc =>
          svc.recommend.map(kind => assertTrue(kind == "beta"))
        }
      }
    ),

    // -----------------------------------------------------------------------
    // Complex matrix: audience-gated integer variable + multi-flag interaction
    //
    // The "audience-premium" datafile enables three flags:
    //   • recommendation_kill_switch  — on for all users (100% rollout, no audience)
    //   • recommendation_variant      — "alpha" for all users (100% rollout, no audience)
    //   • recommendation_rate_limit   — integer variable "value":
    //       100  when user attribute `plan = "premium"` (audience rule fires)
    //        10  for everyone else (audience does not match → FlagNotFound path → default)
    //
    // This demonstrates:
    //   1. Per-user context carrying arbitrary attributes
    //   2. Audience-targeted integer variable evaluation
    //   3. Graceful default when audience rule does not match
    //   4. Parallel evaluation of independent flags (kind + rateLimit read concurrently)
    //   5. Kill-switch interaction with the richer RecommendationResult return type
    // -----------------------------------------------------------------------
    suite("audience-gated rate-limit variable")(
      test("premium user gets elevated rate limit") {
        val premiumCtx = EvaluationContext("user-premium")
          .withAttribute("plan", AttributeValue.StringValue("premium"))
        withProvider("audience-premium") { svc =>
          svc.recommendWithContext(premiumCtx).map { result =>
            assertTrue(result.kind == "alpha") &&
            assertTrue(result.rateLimit == 100)
          }
        }
      },
      test("standard user gets conservative rate limit") {
        val standardCtx = EvaluationContext("user-standard")
          .withAttribute("plan", AttributeValue.StringValue("standard"))
        withProvider("audience-premium") { svc =>
          svc.recommendWithContext(standardCtx).map { result =>
            assertTrue(result.kind == "alpha") &&
            assertTrue(result.rateLimit == 10)
          }
        }
      },
      test("unauthenticated user (no attributes) gets conservative rate limit") {
        val anonCtx = EvaluationContext("user-anon")
        withProvider("audience-premium") { svc =>
          svc.recommendWithContext(anonCtx).map { result =>
            assertTrue(result.kind == "alpha") &&
            assertTrue(result.rateLimit == 10)
          }
        }
      },
      test("kill-switch overrides rate limit — degraded result has rateLimit = 0") {
        // kill-switch-on datafile: kill switch fires (enabled=false), variant and rate-limit
        // are never consulted — result is degraded with rateLimit=0 regardless of plan attribute.
        val premiumCtx = EvaluationContext("user-premium")
          .withAttribute("plan", AttributeValue.StringValue("premium"))
        withProvider("kill-switch-on") { svc =>
          svc.recommendWithContext(premiumCtx).map { result =>
            assertTrue(result.kind == "degraded") &&
            assertTrue(result.rateLimit == 0)
          }
        }
      },
      test("multiple users evaluated against the same provider instance are independent") {
        // Ensures no shared mutable state bleeds across EvaluationContext calls on one provider.
        val premiumCtx  = EvaluationContext("user-p").withAttribute("plan", AttributeValue.StringValue("premium"))
        val standardCtx = EvaluationContext("user-s").withAttribute("plan", AttributeValue.StringValue("standard"))
        withProvider("audience-premium") { svc =>
          for {
            results <- svc
              .recommendWithContext(premiumCtx)
              .zipPar(svc.recommendWithContext(standardCtx))
            (premiumResult, standardResult) = results
          } yield assertTrue(premiumResult.rateLimit == 100) &&
            assertTrue(standardResult.rateLimit == 10) &&
            assertTrue(premiumResult.kind == standardResult.kind)
        }
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
