package zio.openfeature

import zio._
import zio.test._
import zio.stream.SubscriptionRef
import zio.openfeature.internal.{ProviderEvaluations, ProviderStatusMachine}
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch

/** Bridge-level tests (#244): provider-identity gating, PROVIDER_FATAL mapping, terminal-state stickiness, and truthful
  * event payloads. Handlers are driven directly via the extracted `onReadyEvent`/`onErrorEvent`/ `onStaleEvent` methods
  * with builder-constructed `EventDetails`, so no SDK emitter thread is involved.
  */
object ProviderStatusBridgeSpec extends ZIOSpecDefault {

  private def uniqueDomain(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  /** Provider named `nm` whose initialize() blocks so the async build never fires PROVIDER_READY; the test drives the
    * shared status ref directly. The gate is released on shutdown() (scope close), so no executor thread leaks.
    */
  private class InertProvider(nm: String) extends FeatureProvider {
    private val gate = new CountDownLatch(1)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = nm }
    override def getState: ProviderState                    = ProviderState.NOT_READY
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = gate.countDown()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  /** Mimics the SDK's MultiProvider: no metadata name at registration time (the async build captures "unknown"), while
    * the provider's own later events carry a real name.
    */
  private class LateMetadataProvider extends InertProvider("late") {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = null
  }

  private def buildProvider(
    ref: SubscriptionRef[ProviderStatus],
    provider: FeatureProvider
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.buildAsync(
      provider,
      domain = Some(uniqueDomain("bridge")),
      version = None,
      initialHooks = Nil,
      statusRef = Some(ref),
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = 1.hour
    )

  private def build(ref: SubscriptionRef[ProviderStatus], name: String): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.buildAsync(
      new InertProvider(name),
      domain = Some(uniqueDomain("bridge")),
      version = None,
      initialHooks = Nil,
      statusRef = Some(ref),
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = 1.hour
    )

  private def details(name: String, message: String = null, code: OFErrorCode = null): EventDetails = {
    val b = EventDetails.builder()
    if (name != null) b.providerName(name)
    if (message != null) b.message(message)
    if (code != null) b.errorCode(code)
    b.build().asInstanceOf[EventDetails]
  }

  def spec = suite("ProviderStatusBridgeSpec")(
    test("mismatched provider name: status unchanged, event still published under the stamped name") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
        out <- ZIO.scoped {
          build(ref, "current").flatMap { ff =>
            for {
              collected <- Ref.make(List.empty[ProviderEvent])
              cancel    <- ff.on(ProviderEventType.Error, e => collected.update(_ :+ e))
              _         <- ff.onErrorEvent(details("stale-other", message = "boom"))
              evs       <- Live.live(collected.get.repeatUntil(_.nonEmpty).timeout(5.seconds)).map(_.getOrElse(Nil))
              status    <- ff.providerStatus
              _         <- cancel
            } yield assertTrue(
              status == ProviderStatus.Ready, // mismatch => status untouched
              evs.collectFirst { case e: ProviderEvent.Error => e.providerMetadata.name }.contains("stale-other")
            )
          }
        }
      } yield out
    },
    test("indeterminate current name (unknown) fails open: a named event from our own provider drives status") {
      // The SDK's MultiProvider builds its metadata name inside initialize(), so the async build captured "unknown";
      // its own later READY carries the real name. The guard must not reject the provider's own event.
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          buildProvider(ref, new LateMetadataProvider).flatMap { ff =>
            ff.onReadyEvent(details("multiprovider")) *> ff.providerStatus.map(s =>
              assertTrue(s == ProviderStatus.Ready)
            )
          }
        }
      } yield out
    },
    test("lazy-metadata provider: a READY event refreshes providerNameRef from unknown to the real name (#297)") {
      // buildAsync captured "unknown" for a LateMetadataProvider (metadata name only materializes after init). When
      // the provider's own READY arrives stamped with the real name, providerNameRef must be refreshed so
      // providerMetadata reports the real name AND the identity guard regains discrimination.
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          buildProvider(ref, new LateMetadataProvider).flatMap { ff =>
            for {
              beforeName <- ff.providerMetadata.map(_.name)
              _          <- ff.onReadyEvent(details("multiprovider"))
              afterName  <- ff.providerMetadata.map(_.name)
            } yield assertTrue(
              beforeName == FeatureFlags.UnknownProviderName,
              afterName == "multiprovider"
            )
          }
        }
      } yield out
    },
    test("null provider name fails open: transition applies") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          build(ref, "current").flatMap { ff =>
            ff.onReadyEvent(details(null)) *> ff.providerStatus.map(s => assertTrue(s == ProviderStatus.Ready))
          }
        }
      } yield out
    },
    test("PROVIDER_ERROR with PROVIDER_FATAL => Fatal, sticky against a later READY, evaluations fail ProviderFatal") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
        out <- ZIO.scoped {
          build(ref, "current").flatMap { ff =>
            for {
              _        <- ff.onErrorEvent(details("current", message = "dead", code = OFErrorCode.PROVIDER_FATAL))
              afterErr <- ff.providerStatus
              _        <- ff.onReadyEvent(details("current")) // must be ignored — Fatal is sticky
              afterRdy <- ff.providerStatus
              eval     <- ff.boolean("x", default = true).either
            } yield assertTrue(
              afterErr == ProviderStatus.Fatal,
              afterRdy == ProviderStatus.Fatal,
              eval == Left(FeatureFlagError.ProviderFatal)
            )
          }
        }
      } yield out
    },
    test("Error => Ready on a current-provider READY is accepted (no time guard)") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Error)
        out <- ZIO.scoped {
          build(ref, "current").flatMap { ff =>
            ff.onReadyEvent(details("current")) *> ff.providerStatus.map(s => assertTrue(s == ProviderStatus.Ready))
          }
        }
      } yield out
    },
    test("EventStale only transitions from Ready; a stale event over Error is ignored") {
      for {
        refR <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
        refE <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Error)
        fromReady <- ZIO.scoped {
          build(refR, "current").flatMap(ff => ff.onStaleEvent(details("current")) *> ff.providerStatus)
        }
        fromError <- ZIO.scoped {
          build(refE, "current").flatMap(ff => ff.onStaleEvent(details("current")) *> ff.providerStatus)
        }
      } yield assertTrue(fromReady == ProviderStatus.Stale, fromError == ProviderStatus.Error)
    },
    test("after shutdown, late error/stale events cannot move status off terminal NotReady") {
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
        out <- ZIO.scoped {
          build(ref, "current").flatMap { ff =>
            for {
              _         <- ff.shutdown
              afterShut <- ff.providerStatus
              // The status gate (applySignal) runs before the publish; after shutdown the event hub is closed, so the
              // publish step interrupts (in production runHandler's catchAllCause swallows it). Tolerate it with .exit.
              _          <- ff.onErrorEvent(details("current", message = "late error")).exit
              afterErr   <- ff.providerStatus
              _          <- ff.onStaleEvent(details("current")).exit
              afterStale <- ff.providerStatus
              _          <- ff.onReadyEvent(details("current")).exit
              afterReady <- ff.providerStatus
            } yield assertTrue(
              afterShut == ProviderStatus.NotReady,
              afterErr == ProviderStatus.NotReady,
              afterStale == ProviderStatus.NotReady,
              afterReady == ProviderStatus.NotReady
            )
          }
        }
      } yield out
    },
    // --- Duplicate-event behaviour, pinned ahead of spec v0.9.0 Phase 2 (#332) ---
    //
    // These two are characterization tests: they pass today and are expected to. Their job is to make a future
    // change visible rather than to prove a fix. Under spec v0.9.0 providers emit their own lifecycle events while
    // the SDK still synthesizes them on the legacy path, so a provider that adopts emission early produces a
    // duplicate READY. The spec's appendix-e calls those duplicates "expected legacy behavior" — but that verdict
    // is about the *SDK's* status, and the two tests below record that the two halves of this library disagree on
    // how tolerable they are. That asymmetry is the whole reason Phase 2 defers provider-side emission.
    test("a duplicate READY leaves status Ready — the status machine is idempotent") {
      // Asserting the readback alone would be satisfied by a machine that blindly re-asserts Ready, so the
      // mechanism is pinned directly too: `transition` must return None (no transition at all) for a repeat
      // READY. Without this, the test's name would claim more than it checks.
      val repeatReady = ProviderStatusMachine.transition(
        ProviderStatus.Ready,
        ProviderStatusMachine.Signal.EventReady,
        ProviderStatusMachine.Context(everReady = true, swapInProgress = false, shutdownCompleted = false)
      )
      ZIO.scoped {
        for {
          ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
          ff  <- build(ref, "current")
          _   <- ff.onReadyEvent(details("current"))
          s1  <- ff.providerStatus
          _   <- ff.onReadyEvent(details("current"))
          s2  <- ff.providerStatus
        } yield assertTrue(s1 == ProviderStatus.Ready, s2 == ProviderStatus.Ready, repeatReady.isEmpty)
      }
    },
    test("but the event hub delivers BOTH READYs to observers — duplicates are user-visible") {
      ZIO.scoped {
        for {
          ref   <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
          ff    <- build(ref, "current")
          count <- Ref.make(0)
          both  <- Promise.make[Nothing, Unit]
          // `on` establishes the hub subscription before returning, so no event can slip in between registering
          // and firing. Status is NotReady here, so the spec-5.3.3 immediate-fire does not add a phantom count.
          cancel <- ff.on(
            ProviderEventType.Ready,
            _ => count.updateAndGet(_ + 1).flatMap(n => both.succeed(()).when(n >= 2)).unit
          )
          _ <- ff.onReadyEvent(details("current"))
          _ <- ff.onReadyEvent(details("current"))
          _ <- both.await.timeoutFail(new RuntimeException("hub delivered fewer than two READY events"))(10.seconds)
          n <- count.get
          _ <- cancel
          // Delivered twice even though the machine transitioned once. Emitting init events from our own providers
          // while the SDK still synthesizes them would therefore surface as duplicate READYs in USER handlers — a
          // visible regression, not internal noise. If Phase 2 makes this 1, that is the deliberate flip.
        } yield assertTrue(n == 2)
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
