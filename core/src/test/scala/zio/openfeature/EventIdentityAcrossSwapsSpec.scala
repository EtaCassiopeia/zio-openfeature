package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testsupport.LeakCheck
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}

// Per-provider identity across a swap, detaching handlers and streams, and the status a subscriber sees.
object EventIdentityAcrossSwapsSpec extends ZIOSpecDefault {

  final class Emitting(name: String) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    private def details(flag: String) =
      ProviderEventDetails.builder().flagsChanged(java.util.List.of(flag)).message(flag).build()
    def config(flag: String): Unit = emitProviderConfigurationChanged(details(flag)).await()
    def ready(): Unit              = emitProviderReady(ProviderEventDetails.builder().build()).await()
    def error(): Unit              = emitProviderError(ProviderEventDetails.builder().message("boom").build()).await()
    def stale(): Unit              = emitProviderStale(ProviderEventDetails.builder().message("old").build()).await()

    override def getBooleanEvaluation(
      key: String,
      default: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(
      key: String,
      default: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] =
      ProviderEvaluations.of[String](default, "DEFAULT")
    override def getIntegerEvaluation(
      key: String,
      default: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = ProviderEvaluations.of[java.lang.Integer](default, "DEFAULT")
    override def getDoubleEvaluation(
      key: String,
      default: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = ProviderEvaluations.of[java.lang.Double](default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext): ProviderEvaluation[Value] =
      ProviderEvaluations.of[Value](default, "DEFAULT")
  }

  private def blocking(f: => Unit): UIO[Unit] = ZIO.attemptBlocking(f).orDie

  private def statusOf(e: ProviderEvent): Option[ProviderStatus] = e match {
    case _: ProviderEvent.Ready => Some(ProviderStatus.Ready)
    case _: ProviderEvent.Error => Some(ProviderStatus.Error)
    case _: ProviderEvent.Stale => Some(ProviderStatus.Stale)
    case _                      => None
  }

  def spec = suite("EventIdentityAcrossSwapsSpec")(
    test("after a swap, a pre-swap stream and handler see none of the old provider's events, and all of the new one's") {
      ZIO.scoped {
        for {
          a   <- ZIO.succeed(new Emitting("A"))
          b   <- ZIO.succeed(new Emitting("B"))
          env <- FeatureFlags.fromProvider(a).build
          ff    = env.get[FeatureFlags]
          admin = env.get[FeatureFlagsAdmin]
          _      <- ff.awaitReady(5.seconds)
          seen   <- Ref.make(Chunk.empty[ProviderEvent])
          viaOn  <- Ref.make(Chunk.empty[ProviderEvent])
          stream <- ff.subscribeEvents
          done   <- Promise.make[Nothing, Unit]
          // The SDK runs a provider's handlers on a pool with no order between them (docs/lifecycle.md:26,
          // docs/design/lifecycle.md:108-112), so wait for every new event rather than for the last one.
          _ <- stream
            .tap(e => seen.update(_ :+ e))
            .collect { case ProviderEvent.ConfigurationChanged(f, _, _) => f }
            .scan(Set.empty[String])(_ ++ _)
            .takeUntil(fs => Set("new-1", "new-2", "end").subsetOf(fs))
            .runDrain
            .zipRight(done.succeed(()))
            .forkScoped
          _ <- ZIO.foreachDiscard(List(ProviderEventType.ConfigurationChanged, ProviderEventType.Error))(t =>
            ZIO.acquireRelease(admin.on(t, e => viaOn.update(_ :+ e)))(c => c)
          )
          _ <- admin.setProvider(b).orDie
          // The replaced provider keeps emitting; the new one emits, then a sentinel.
          _  <- blocking { a.config("old-1"); a.error(); a.config("old-2") }
          _  <- blocking { b.config("new-1"); b.config("new-2"); b.config("end") }
          ok <- done.await.timeout(5.seconds)
          _  <- viaOn.get
            .repeatUntil(_.count(_.eventType == ProviderEventType.ConfigurationChanged) >= 3)
            .timeout(5.seconds)
          // A grace period for a late delivery of the old provider's events, which are dispatched on other threads.
          _       <- ZIO.sleep(300.millis)
          all     <- seen.get
          handled <- viaOn.get
          names = all.map(_.metadata.name).toSet
          flags = all.collect { case ProviderEvent.ConfigurationChanged(f, _, _) => f }.flatten
          status <- ff.providerStatus
        } yield assertTrue(
          ok.isDefined,
          !names.contains("A"),
          !handled.exists(_.metadata.name == "A"),
          flags.sorted == Chunk("end", "new-1", "new-2"),
          handled.collect { case ProviderEvent.ConfigurationChanged(f, _, _) => f }.flatten.sorted == Chunk(
            "end",
            "new-1",
            "new-2"
          ),
          status == ProviderStatus.Ready
        )
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.nonFlaky(20),
    test("once quiescent, the last status event a subscriber saw matches providerStatus") {
      ZIO.scoped {
        for {
          b   <- ZIO.succeed(new Emitting("B"))
          env <- FeatureFlags.fromProvider(new Emitting("A")).build
          ff    = env.get[FeatureFlags]
          admin = env.get[FeatureFlagsAdmin]
          _      <- ff.awaitReady(5.seconds)
          seen   <- Ref.make(Chunk.empty[ProviderEvent])
          stream <- ff.subscribeEvents
          _      <- stream.foreach(e => seen.update(_ :+ e)).forkScoped
          _      <- admin.setProvider(b).orDie
          // B's own READY from its initialization first: a READY handled late is delivered as sent (CHANGELOG.md:502).
          _ <- seen.get
            .repeatUntil(_.exists(e => e.eventType == ProviderEventType.Ready && e.metadata.name == "B"))
            .timeout(5.seconds)
          // One emission at a time, each awaited at the subscriber: the SDK gives two in flight no order.
          steps = List[(() => Unit, ProviderEventType)](
            (() => b.error(), ProviderEventType.Error),
            (() => b.stale(), ProviderEventType.Stale),
            (() => b.ready(), ProviderEventType.Ready),
            (() => b.error(), ProviderEventType.Error)
          )
          _ <- ZIO.foreachDiscard(steps.zipWithIndex) { case ((emit, _), i) =>
            blocking(emit()) *>
              seen.get
                .map(_.count(e => statusOf(e).isDefined && e.metadata.name == "B"))
                .repeatUntil(_ > i + 1)
                .timeout(5.seconds)
          }
          all <- seen.get
          last = all.filter(_.metadata.name == "B").flatMap(statusOf(_)).lastOption
          status <- ff.providerStatus
        } yield assertTrue(last.contains(status), status == ProviderStatus.Error)
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.nonFlaky(20),
    test("cancelled handlers and closed streams leave no fiber behind, even when registered during swaps") {
      for {
        out <- ZIO.scoped {
          for {
            env <- FeatureFlags.fromProvider(new Emitting("A")).build
            ff    = env.get[FeatureFlags]
            admin = env.get[FeatureFlagsAdmin]
            _        <- ff.awaitReady(5.seconds)
            baseline <- LeakCheck.snapshot
            swaps    <- ZIO.foreachDiscard(1 to 5)(i => admin.setProvider(new Emitting(s"P$i")).ignore).fork
            _        <- ZIO.foreachParDiscard(1 to 40) { i =>
              val register = (i % 9) match {
                case 0 => admin.on(ProviderEventType.Ready, _ => ZIO.unit)
                case 1 => admin.on(ProviderEventType.Error, _ => ZIO.unit)
                case 2 => admin.on(ProviderEventType.Stale, _ => ZIO.unit)
                case 3 => admin.on(ProviderEventType.ConfigurationChanged, _ => ZIO.unit)
                case 4 => admin.on(ProviderEventType.Reconnecting, _ => ZIO.unit)
                case 5 => admin.onProviderReady(_ => ZIO.unit)
                case 6 => admin.onProviderError((_, _) => ZIO.unit)
                case 7 => admin.onProviderStale((_, _) => ZIO.unit)
                case _ => admin.onConfigurationChanged((_, _) => ZIO.unit)
              }
              val stream = ZIO.scoped(ff.subscribeEvents.flatMap(_.take(1).runDrain.timeout(1.milli)))
              register.flatMap(cancel => ZIO.yieldNow *> cancel) *> stream.unit
            }
            _     <- swaps.join
            leaks <- LeakCheck.awaitNoLeaks(baseline, 5.seconds)
          } yield leaks
        }
      } yield assertTrue(out.isEmpty)
    } @@ TestAspect.withLiveClock @@ TestAspect.nonFlaky(10)
  ) @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
}
