package zio.openfeature

import zio.openfeature.internal.{ManagedProvider, ProviderEvaluations}

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  EventProviderAccess,
  ImmutableContext,
  Metadata,
  OpenFeatureAPI,
  ProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.test._

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

// The registered wrapper observes its provider: initialize() per domain and cycle, shutdown() once per cycle, and the
// provider's events in the order it received them.
object ManagedProviderSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  /** A provider whose `initialize()` blocks on `gate` and then throws `failWith` if set; counts its shutdowns. */
  private class Delegate extends EventProvider {
    val gate      = new AtomicReference[CountDownLatch](new CountDownLatch(0))
    val failWith  = new AtomicReference[Option[Throwable]](None)
    val shutdowns = new AtomicInteger(0)
    val initsSeen = new AtomicInteger(0)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                    = new Metadata { override def getName: String = "delegate" }
    override def initialize(c: OFEvaluationContext): Unit = {
      initsSeen.incrementAndGet()
      val _ = gate.get().await(10, TimeUnit.SECONDS)
      failWith.get().foreach(e => throw e)
    }
    override def shutdown(): Unit = { shutdowns.incrementAndGet(); () }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")

    def emitAndWait(event: ProviderEvent): Unit = { emit(event, ProviderEventDetails.builder().build()).await(); () }
  }

  private def initOn(wrapper: ManagedProvider, domain: Option[String]): UIO[Fiber[Throwable, Unit]] =
    ZIO.attemptBlocking(domain.fold(wrapper.initialize(ctx))(d => wrapper.initialize(ctx, d))).fork

  private def eventually(cond: => Boolean): UIO[Boolean] =
    Live.live(ZIO.succeed(cond).repeatUntil(identity).timeout(5.seconds)).map(_.contains(true))

  def spec = suite("ManagedProviderSpec")(
    test("initialize() is counted per domain while it runs, and its outcome completes when it returns") {
      val delegate = new Delegate
      delegate.gate.set(new CountDownLatch(1))
      val wrapper = ManagedProvider(delegate)
      for {
        outcome <- wrapper.initOutcome.fork // awaited before the call starts
        running <- initOn(wrapper, Some("a"))
        entered <- eventually(wrapper.initsInFlight(Some("a")) == 1)
        other = wrapper.initsInFlight(None)
        _    <- ZIO.succeed(delegate.gate.get().countDown())
        _    <- running.join
        done <- outcome.join.timeout(5.seconds).exit
        after = wrapper.initsInFlight(Some("a"))
      } yield assertTrue(entered, other == 0, done == Exit.succeed(Some(())), after == 0)
    } @@ TestAspect.withLiveClock,
    test("the outcome fails with the exception initialize() threw") {
      val delegate = new Delegate
      val boom     = new RuntimeException("boom")
      delegate.failWith.set(Some(boom))
      val wrapper = ManagedProvider(delegate)
      for {
        thrown  <- initOn(wrapper, None).flatMap(_.join).either
        outcome <- wrapper.initOutcome.either
      } yield assertTrue(
        thrown.left.toOption.exists(_ eq boom),
        outcome.left.toOption.exists(_ eq boom),
        wrapper.initsInFlight(None) == 0
      )
    },
    test("a later initialize() in the same cycle gets a fresh outcome") {
      val delegate = new Delegate
      delegate.failWith.set(Some(new RuntimeException("first")))
      val wrapper = ManagedProvider(delegate)
      for {
        _      <- initOn(wrapper, None).flatMap(_.join).either
        _      <- ZIO.succeed(delegate.failWith.set(None))
        _      <- initOn(wrapper, None).flatMap(_.join)
        second <- wrapper.initOutcome.either
      } yield assertTrue(second == Right(()))
    },
    test("shutdown() reaches the provider once per cycle, and again after a new initialize()") {
      val delegate = new Delegate
      val wrapper  = ManagedProvider(delegate)
      for {
        _ <- initOn(wrapper, None).flatMap(_.join)
        _ <- ZIO.attemptBlocking { wrapper.shutdown(); wrapper.shutdown() }
        once = (delegate.shutdowns.get(), wrapper.shutDownInCycle)
        _ <- initOn(wrapper, None).flatMap(_.join)
        fresh = wrapper.shutDownInCycle
        _ <- ZIO.attemptBlocking(wrapper.shutdown())
      } yield assertTrue(once == ((1, true)), !fresh, delegate.shutdowns.get() == 2)
    },
    test("the provider's events are forwarded again in the cycle after a shutdown") {
      val delegate = new Delegate
      val wrapper  = ManagedProvider(delegate)
      val seen     = new CopyOnWriteArrayList[ProviderEvent]()
      EventProviderAccess.attach(wrapper, (e, _) => { seen.add(e); () })
      for {
        _      <- initOn(wrapper, None).flatMap(_.join)
        _      <- ZIO.attemptBlocking(delegate.emitAndWait(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED))
        first  <- eventually(seen.size == 1)
        _      <- ZIO.attemptBlocking(wrapper.shutdown())
        _      <- initOn(wrapper, None).flatMap(_.join)
        _      <- ZIO.attemptBlocking(delegate.emitAndWait(ProviderEvent.PROVIDER_STALE))
        second <- eventually(seen.size == 2)
        _      <- ZIO.attemptBlocking(wrapper.shutdown())
      } yield assertTrue(first, second)
    } @@ TestAspect.withLiveClock,
    test("a shutdown that overlaps the next initialize() leaves the new cycle's events flowing") {
      val release  = new CountDownLatch(1)
      val entered  = new CountDownLatch(1)
      val delegate = new Delegate {
        override def shutdown(): Unit = {
          entered.countDown(); val _ = release.await(10, TimeUnit.SECONDS); super.shutdown()
        }
      }
      val wrapper = ManagedProvider(delegate)
      val seen    = new CopyOnWriteArrayList[ProviderEvent]()
      EventProviderAccess.attach(wrapper, (e, _) => { seen.add(e); () })
      for {
        _        <- initOn(wrapper, None).flatMap(_.join)
        stopping <- ZIO.attemptBlocking(wrapper.shutdown()).fork
        _        <- ZIO.attemptBlocking(entered.await(5, TimeUnit.SECONDS))
        _        <- initOn(wrapper, None).flatMap(_.join)
        _        <- ZIO.succeed(release.countDown())
        _        <- stopping.join
        _        <- ZIO.attemptBlocking(delegate.emitAndWait(ProviderEvent.PROVIDER_STALE))
        arrived  <- eventually(seen.contains(ProviderEvent.PROVIDER_STALE))
        cycle = wrapper.shutDownInCycle
        _ <- ZIO.attemptBlocking(wrapper.shutdown())
      } yield assertTrue(arrived, !cycle)
    } @@ TestAspect.withLiveClock,
    test("a provider's ERROR then STALE, a hundred times over, leaves the slot STALE every time") {
      ZIO
        .attemptBlocking {
          val delegate = new Delegate
          val wrapper  = ManagedProvider(delegate)
          val api      = OpenFeatureAPI.createIsolated()
          val domain   = s"managed-${java.util.UUID.randomUUID()}"
          val counts   = new ConcurrentHashMap[ProviderEvent, AtomicInteger]()
          try {
            api.setProviderAndWait(domain, wrapper)
            List(ProviderEvent.PROVIDER_ERROR, ProviderEvent.PROVIDER_STALE).foreach { e =>
              counts.put(e, new AtomicInteger(0))
              api.getClient(domain).on(e, _ => { counts.get(e).incrementAndGet(); () })
            }
            (1 to 100).iterator
              .map { r =>
                delegate.emitAndWait(ProviderEvent.PROVIDER_ERROR)
                delegate.emitAndWait(ProviderEvent.PROVIDER_STALE)
                val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
                while (
                  (counts
                    .get(ProviderEvent.PROVIDER_ERROR)
                    .get() < r || counts.get(ProviderEvent.PROVIDER_STALE).get() < r)
                  && java.lang.System.nanoTime() < deadline
                ) Thread.sleep(1)
                val state = api.getClient(domain).getProviderState
                if (state != ProviderState.STALE) Some(s"round $r ended $state") else None
              }
              .collectFirst { case Some(m) => m }
          } finally api.shutdown()
        }
        .map(failure => assertTrue(failure.isEmpty))
    },
    test("wrapping an EventProvider already attached elsewhere fails at construction") {
      val delegate = new Delegate
      EventProviderAccess.attach(delegate, (_, _) => ())
      ZIO
        .attempt(ManagedProvider(delegate))
        .either
        .map(r => assertTrue(r.left.toOption.exists(_.isInstanceOf[IllegalStateException])))
    },
    test("it overrides every FeatureProvider method, so none falls back to a default instead of the provider's") {
      def sig(m: java.lang.reflect.Method) = (m.getName, m.getParameterTypes.toList)
      val surface                          = classOf[dev.openfeature.sdk.FeatureProvider].getMethods.toList
        .filter(m => !java.lang.reflect.Modifier.isStatic(m.getModifiers) && m.getDeclaringClass != classOf[Object])
        .map(sig)
        .distinct
      val declared = classOf[ManagedProvider].getDeclaredMethods.map(sig).toSet
      val missing  = surface.filterNot(declared.contains).map(_._1)
      assertTrue(missing.isEmpty) ?? s"ManagedProvider does not override: ${missing.mkString(", ")}"
    },
    test("apply returns a wrapper unchanged, and unwrap returns the provider behind it") {
      val delegate = new Delegate
      val wrapper  = ManagedProvider(delegate)
      assertTrue(
        ManagedProvider(wrapper) eq wrapper,
        ManagedProvider.unwrap(wrapper) eq delegate,
        ManagedProvider.unwrap(delegate) eq delegate,
        wrapper.getMetadata.getName == "delegate"
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)
}
