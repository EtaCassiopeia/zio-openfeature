package zio.openfeature

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import zio._
import zio.stream.SubscriptionRef
import zio.test._
import zio.openfeature.internal.{
  EventBridge,
  FeatureFlagsFactories,
  Lifecycle,
  LifecycleInput,
  LifecycleProbe,
  ProviderEvaluations,
  ProviderName,
  ProviderStatusMachine
}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  FeatureProvider,
  ImmutableMetadata,
  Metadata,
  NoOpProvider,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderState,
  Value
}

/** An event of a provider that a swap replaced does not reach the instance's observers once the swap has started. The
  * SDK runs a handler on its own executor some time after it picked the handler for the event, so such an event can
  * reach the bridge after the swap; the tests hold one there, as a starved handler thread would.
  */
object ReplacedProviderEventsSpec extends ZIOSpecDefault {

  private class Named(name: String) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  private val Marker = "marker"

  /** An event as the SDK stamps it; `marker` tells the test's last event from every other. */
  private def details(name: String, marker: Boolean = false): EventDetails = {
    val b = EventDetails.builder().providerName(name)
    if (marker) b.eventMetadata(ImmutableMetadata.builder().addBoolean(Marker, true).build())
    b.build().asInstanceOf[EventDetails]
  }

  private def changed(name: String, flag: String): EventDetails =
    EventDetails
      .builder()
      .providerName(name)
      .flagsChanged(java.util.Arrays.asList(flag))
      .build()
      .asInstanceOf[EventDetails]

  private def within[A](wait: UIO[A]): UIO[A] =
    wait.timeoutFail(new RuntimeException("timed out"))(10.seconds).orDie

  /** Registers a handler for `eventType`; the effect it returns sends the installed provider's own event through `send`
    * and answers with the provider names the handler received up to it. Events reach a handler in the order they were
    * published, so nothing published earlier is still on its way.
    */
  private def namesUpTo(ff: FeatureFlagsLive, eventType: ProviderEventType, installed: String)(
    send: EventDetails => UIO[Unit]
  ): UIO[UIO[Vector[String]]] =
    for {
      seen   <- Ref.make(Vector.empty[String])
      marked <- Promise.make[Nothing, Unit]
      _      <- ff.on(
        eventType,
        e => seen.update(_ :+ e.metadata.name) *> marked.succeed(()).when(e.metadata.name == installed).unit
      )
    } yield send(details(installed)) *> within(marked.await) *> seen.get

  /** Holds the first fiber to reach `point` once armed. */
  final private class Hold(
    point: LifecycleProbe.Point,
    reached: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit]
  ) extends LifecycleProbe {
    val armed                                  = new AtomicBoolean(false)
    def at(p: LifecycleProbe.Point): UIO[Unit] =
      ZIO.suspendSucceed {
        if (p == point && armed.compareAndSet(true, false)) reached.succeed(()) *> release.await else ZIO.unit
      }
  }

  /** An instance on `first`, whose own READY has been handled. */
  private def onFirst(probe: LifecycleProbe): ZIO[Scope, Throwable, FeatureFlagsLive] = {
    val ready = new CountDownLatch(1)
    FeatureFlagsFactories
      .build(
        new Named("first"),
        domain = Some(s"replaced-${java.util.UUID.randomUUID()}"),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(OpenFeatureAPI.createIsolated()),
        onReady = Some(ready),
        probe = probe
      )
      .tap(_ => ZIO.attemptBlocking(ready.await(10, TimeUnit.SECONDS)))
  }

  /** Registers a Ready handler now; the effect it returns sends a marker event and answers with the names of the Ready
    * events the handler received up to it. Events reach a handler in the order they were published, so nothing
    * published before the marker is still on its way.
    */
  private def readyNames(ff: FeatureFlagsLive): UIO[UIO[Vector[String]]] =
    for {
      seen   <- Ref.make(Vector.empty[String])
      marked <- Promise.make[Nothing, Unit]
      _      <- ff.on(
        ProviderEventType.Ready,
        {
          case ProviderEvent.Ready(meta, eventMeta) =>
            seen.update(_ :+ meta.name) *> marked.succeed(()).when(eventMeta.getBoolean(Marker).contains(true)).unit
          case _ => ZIO.unit
        }
      )
    } yield ff.onSdkEvent(OFProviderEvent.PROVIDER_READY, details("second", marker = true)) *>
      within(marked.await) *> seen.get

  /** A READY of `first` held at `point` across a swap to `second`, then let go. */
  private def lateReady(point: LifecycleProbe.Point) =
    ZIO.scoped {
      for {
        reached <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        hold = new Hold(point, reached, release)
        ff    <- onFirst(hold)
        _     <- ZIO.succeed(hold.armed.set(true))
        late  <- ff.onSdkEvent(OFProviderEvent.PROVIDER_READY, details("first")).fork
        _     <- reached.await
        _     <- ff.setProvider(new Named("second"))
        names <- readyNames(ff)
        _     <- release.succeed(())
        _     <- late.join
        seen  <- names
      } yield assertTrue(seen.nonEmpty, seen.forall(_ == "second"))
    }

  def spec = suite("ReplacedProviderEventsSpec")(
    test("a READY of the replaced provider that reaches the bridge after the swap is not delivered") {
      lateReady(LifecycleProbe.Point.DeliverEvent("PROVIDER_READY"))
    },
    test("a READY of the replaced provider that passed the identity guard before the swap is not delivered after it") {
      lateReady(LifecycleProbe.Point.ApplySignal(ProviderStatusMachine.Signal.EventReady))
    },
    test("an ERROR or STALE of the replaced provider is not delivered after the swap") {
      ZIO.scoped {
        for {
          ff     <- onFirst(LifecycleProbe.none)
          _      <- ff.setProvider(new Named("second"))
          errors <- namesUpTo(ff, ProviderEventType.Error, "second")(ff.onErrorEvent)
          stales <- namesUpTo(ff, ProviderEventType.Stale, "second")(ff.onStaleEvent)
          _      <- ff.onErrorEvent(details("first"))
          _      <- ff.onStaleEvent(details("first"))
          e      <- errors
          st     <- stales
        } yield assertTrue(e == Vector("second"), st == Vector("second"))
      }
    },
    test("a CONFIGURATION_CHANGED of the replaced provider is not delivered after the swap") {
      ZIO.scoped {
        for {
          ff     <- onFirst(LifecycleProbe.none)
          _      <- ff.setProvider(new Named("second"))
          keys   <- Ref.make(Vector.empty[Set[String]])
          marked <- Promise.make[Nothing, Unit]
          _      <- ff.on(
            ProviderEventType.ConfigurationChanged,
            {
              case ProviderEvent.ConfigurationChanged(flags, _, _) =>
                keys.update(_ :+ flags) *> marked.succeed(()).when(flags == Set("live-flag")).unit
              case _ => ZIO.unit
            }
          )
          _   <- ff.onConfigurationChangedEvent(changed("first", "stale-flag"))
          _   <- ff.onConfigurationChangedEvent(changed("second", "live-flag"))
          _   <- within(marked.await)
          got <- keys.get
        } yield assertTrue(got == Vector(Set("live-flag")))
      }
    },
    test("a straggler of the fromAcquireAsync fallback is not delivered after the switch") {
      ZIO.scoped {
        for {
          env <- FeatureFlags
            .fromAcquireAsync(
              ZIO.succeed(new Named("acquired"): FeatureProvider),
              ZIO.succeed(new Named("fallback"): FeatureProvider)
            )
            .build
          ff = env.get[FeatureFlags].asInstanceOf[FeatureFlagsLive]
          // After the switch the instance runs the composed stack, which goes by this name.
          _     <- within(ff.providerMetadata.repeatUntil(_.name == "multiprovider"))
          names <- namesUpTo(ff, ProviderEventType.Stale, "multiprovider")(ff.onStaleEvent)
          _     <- ff.onStaleEvent(details("fallback"))
          seen  <- names
        } yield assertTrue(seen == Vector("multiprovider"))
      }
    },
    test("a step waits for an admitted effect, and a refused effect does not run") {
      for {
        status <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
        lifecycle = new Lifecycle(
          status,
          new NoOpProvider(),
          new AtomicBoolean(false),
          new AtomicLong(0L),
          new AtomicReference[ProviderName](ProviderName.Named("a"))
        )
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        ran      <- Ref.make(false)
        admitted <- lifecycle.whenAdmitted(_.name == ProviderName.Named("a"))(entered.succeed(()) *> release.await).fork
        _        <- entered.await
        step     <- lifecycle.run(LifecycleInput.Idle).fork
        _        <- ZIO.sleep(100.millis)
        during   <- step.poll
        _        <- release.succeed(())
        ok       <- admitted.join
        _        <- step.join
        refused  <- lifecycle.whenAdmitted(_ => false)(ran.set(true))
        didRun   <- ran.get
      } yield assertTrue(during.isEmpty, ok, !refused, !didRun)
    },
    test("the installed provider's ERROR and STALE are still delivered") {
      ZIO.scoped {
        for {
          ff    <- onFirst(LifecycleProbe.none)
          _     <- ff.setProvider(new Named("second"))
          error <- Promise.make[Nothing, String]
          stale <- Promise.make[Nothing, String]
          _     <- ff.on(ProviderEventType.Error, e => error.succeed(e.metadata.name).unit)
          _     <- ff.on(ProviderEventType.Stale, e => stale.succeed(e.metadata.name).unit)
          _     <- ff.onErrorEvent(details("second"))
          _     <- ff.onStaleEvent(details("second"))
          e     <- error.await.timeout(10.seconds)
          s     <- stale.await.timeout(10.seconds)
        } yield assertTrue(e == Some("second"), s == Some("second"))
      }
    },
    test(
      "after a swap fails and rolls back, the failed provider's events are not delivered and the previous one's are"
    ) {
      ZIO.scoped {
        for {
          ff     <- onFirst(LifecycleProbe.none)
          failed <- ff
            .setProvider(new Named("bad") {
              override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("no")
            })
            .either
          seen   <- Ref.make(Vector.empty[String])
          marked <- Promise.make[Nothing, Unit]
          _      <- ff.on(
            ProviderEventType.Stale,
            e => seen.update(_ :+ e.metadata.name) *> marked.succeed(()).when(e.metadata.name == "first").unit
          )
          _     <- ff.onStaleEvent(details("bad"))
          _     <- ff.onStaleEvent(details("first"))
          _     <- within(marked.await)
          names <- seen.get
        } yield assertTrue(failed.isLeft, names == Vector("first"))
      }
    },
    test("an event with no provider name fails open: it is delivered, under the installed provider's name") {
      ZIO.scoped {
        for {
          ff   <- onFirst(LifecycleProbe.none)
          _    <- ff.setProvider(new Named("second"))
          got  <- Promise.make[Nothing, String]
          _    <- ff.on(ProviderEventType.Stale, e => got.succeed(e.metadata.name).unit)
          _    <- ff.onStaleEvent(EventDetails.builder().build().asInstanceOf[EventDetails])
          name <- got.await.timeout(10.seconds)
        } yield assertTrue(name == Some("second"))
      }
    },
    test("attributable: a stamped name must match the installed one, and what cannot be told apart fails open") {
      assertTrue(
        EventBridge.attributable(None, ProviderName.Named("a")),
        EventBridge.attributable(None, ProviderName.Nameless),
        EventBridge.attributable(None, ProviderName.Pending),
        EventBridge.attributable(Some("a"), ProviderName.Pending),
        !EventBridge.attributable(Some("a"), ProviderName.Nameless),
        EventBridge.attributable(Some("a"), ProviderName.Named("a")),
        !EventBridge.attributable(Some("a"), ProviderName.Named("b"))
      )
    }
  ) @@ TestAspect.withLiveClock
}
