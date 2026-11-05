package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEventDetails,
  Value
}
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.stream.ZStream
import zio.test._

import scala.jdk.CollectionConverters._

/** `subscribeEvents` holds its subscription from the moment it returns. Each test keeps the stream from running until
  * the event has been published, which an `on` handler confirms, so none of them depends on how fibers are scheduled.
  */
object EventSubscriptionSpec extends ZIOSpecDefault {

  private class EmittingProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "emitting" }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** An instance, and a way to publish a change and wait until the instance has published it to its subscribers. */
  final private case class Instance(flags: FeatureFlags, publish: String => UIO[Unit])

  private def instance: ZIO[Scope, Throwable, Instance] =
    for {
      provider <- ZIO.succeed(new EmittingProvider)
      env      <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig.default.withIsolatedApi).build
      flags = env.get[FeatureFlags]
      _    <- flags.awaitReady(10.seconds)
      seen <- Ref.make(Set.empty[String])
      _    <- env
        .get[FeatureFlagsAdmin]
        .on(
          ProviderEventType.ConfigurationChanged,
          {
            case ProviderEvent.ConfigurationChanged(keys, _, _) => seen.update(_ ++ keys)
            case _                                              => ZIO.unit
          }
        )
    } yield Instance(
      flags,
      key =>
        ZIO.attemptBlocking {
          provider
            .emitProviderConfigurationChanged(ProviderEventDetails.builder().flagsChanged(List(key).asJava).build())
            .await()
        }.orDie *>
          seen.get
            .repeat(Schedule.recurUntil[Set[String]](_.contains(key)) && Schedule.spaced(1.milli))
            .timeoutFail(new IllegalStateException(s"$key was not published"))(Bound)
            .orDie
            .unit
    )

  private def changes(stream: ZStream[Any, Nothing, ProviderEvent]): ZStream[Any, Nothing, Set[String]] =
    stream.collect { case ProviderEvent.ConfigurationChanged(keys, _, _) => keys }

  // A run that never gets an event ends here as None rather than holding the suite.
  private val Bound = 5.seconds

  def spec = suite("subscribeEvents")(
    test("an event published before the stream first runs is received") {
      ZIO.scoped {
        for {
          live   <- instance
          stream <- live.flags.subscribeEvents
          _      <- live.publish("banner")
          first  <- changes(stream).runHead.timeout(Bound)
        } yield assertTrue(first == Some(Some(Set("banner"))))
      }
    },
    test("the accessor subscribes before it returns") {
      ZIO.scoped {
        for {
          live   <- instance
          stream <- FeatureFlags.subscribeEvents.provideSome[Scope](ZLayer.succeed(live.flags))
          _      <- live.publish("banner")
          first  <- changes(stream).runHead.timeout(Bound)
        } yield assertTrue(first == Some(Some(Set("banner"))))
      }
    },
    test("two subscriptions each receive the event") {
      ZIO.scoped {
        for {
          live   <- instance
          a      <- live.flags.subscribeEvents
          b      <- live.flags.subscribeEvents
          _      <- live.publish("banner")
          firstA <- changes(a).runHead.timeout(Bound)
          firstB <- changes(b).runHead.timeout(Bound)
        } yield assertTrue(firstA == Some(Some(Set("banner"))), firstB == Some(Some(Set("banner"))))
      }
    },
    test("past the hub's capacity before the first read, the newest 256 are kept") {
      ZIO.scoped {
        for {
          live   <- instance
          stream <- live.flags.subscribeEvents
          _      <- ZIO.foreachDiscard(1 to 300)(i => live.publish(s"flag-$i"))
          read   <- changes(stream).take(256).runCollect.timeout(Bound)
        } yield assertTrue(
          read.map(_.head) == Some(Set("flag-45")),
          read.map(_.last) == Some(Set("flag-300"))
        )
      }
    },
    test("an event published before subscribeEvents is not received") {
      ZIO.scoped {
        for {
          live   <- instance
          _      <- live.publish("before")
          stream <- live.flags.subscribeEvents
          _      <- live.publish("after")
          first  <- changes(stream).runHead.timeout(Bound)
        } yield assertTrue(first == Some(Some(Set("after"))))
      }
    },
    test("a running stream ends when the instance shuts down") {
      ZIO.scoped {
        for {
          scope  <- Scope.make
          live   <- scope.extend[Any](instance)
          stream <- live.flags.subscribeEvents
          got    <- Promise.make[Nothing, Set[String]]
          fiber  <- changes(stream).foreach(got.succeed(_).unit).fork
          _      <- live.publish("banner")
          first  <- got.await.timeout(Bound)
          _      <- scope.close(Exit.unit)
          joined <- fiber.join.timeout(Bound)
        } yield assertTrue(first == Some(Set("banner")), joined.isDefined)
      }
    },
    test("a stream not yet run ends empty once the instance has shut down, its unread events dropped") {
      ZIO.scoped {
        for {
          scope  <- Scope.make
          live   <- scope.extend[Any](instance)
          stream <- live.flags.subscribeEvents
          _      <- live.publish("banner")
          _      <- scope.close(Exit.unit)
          read   <- stream.runCollect.timeout(Bound)
        } yield assertTrue(read == Some(Chunk.empty))
      }
    },
    test("subscribing after the instance has shut down gives a stream that ends empty") {
      ZIO.scoped {
        for {
          scope  <- Scope.make
          live   <- scope.extend[Any](instance)
          _      <- scope.close(Exit.unit)
          stream <- live.flags.subscribeEvents
          read   <- stream.runCollect.timeout(Bound)
        } yield assertTrue(read == Some(Chunk.empty))
      }
    },
    test("a run after the first has ended is empty, even for a later event") {
      ZIO.scoped {
        for {
          live   <- instance
          stream <- live.flags.subscribeEvents
          _      <- live.publish("first")
          head   <- changes(stream).runHead.timeout(Bound)
          _      <- live.publish("second")
          again  <- stream.runCollect.timeout(Bound)
        } yield assertTrue(head == Some(Some(Set("first"))), again == Some(Chunk.empty))
      }
    },
    test("closing the subscription's scope ends its stream and leaves the instance serving") {
      ZIO.scoped {
        for {
          live   <- instance
          inner  <- Scope.make
          stream <- inner.extend[Any](live.flags.subscribeEvents)
          got    <- Promise.make[Nothing, Set[String]]
          fiber  <- changes(stream).foreach(got.succeed(_).unit).fork
          _      <- live.publish("banner")
          first  <- got.await.timeout(Bound)
          _      <- inner.close(Exit.unit)
          ended  <- fiber.join.timeout(Bound)
          _      <- live.publish("later") // the instance's own `on` handler still gets this
          value  <- live.flags.boolean("any", default = false)
          st     <- live.flags.providerStatus
        } yield assertTrue(
          first == Some(Set("banner")),
          ended.isDefined,
          value,
          st == ProviderStatus.Ready
        )
      }
    },
    test("an implementor that does not override it gets a working stream from the default") {
      val event = ProviderEvent.ConfigurationChanged(Set("banner"), ProviderMetadata("stub"))
      ZIO.scoped {
        new Stub(ZStream(event)).subscribeEvents.flatMap(_.runCollect).map(read => assertTrue(read == Chunk(event)))
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

  // The one abstract member reached here is `events`.
  final private class Stub(stream: ZStream[Any, Nothing, ProviderEvent]) extends FeatureFlags {
    override def events: ZStream[Any, Nothing, ProviderEvent]                                             = stream
    override def valueDetails[A: FlagType](k: String, d: A, ctx: EvaluationContext, o: EvaluationOptions) = ???
    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def globalContext: UIO[EvaluationContext]                                                    = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                                      = ???
    override def clientContext: UIO[EvaluationContext]                                                    = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]            = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError.TransactionError], TransactionResult[A]]                                 = ???
    override def inTransaction: UIO[Boolean]                                                                      = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                                       = ???
    override def providerStatus: UIO[ProviderStatus]                                                              = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                                = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                          = ???
    override def clientMetadata: UIO[ClientMetadata]                                                              = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                            = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                                    = ???
    override def clearHooks: UIO[Unit]                                                                            = ???
    override def hooks: UIO[List[FeatureHook]]                                                                    = ???
    override def track(eventName: String): IO[FeatureFlagError.EvaluationError, Unit]                             = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError.EvaluationError, Unit] = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError.EvaluationError, Unit] =
      ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError.EvaluationError, Unit] =
      ???
  }
}
