package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.{FatalError, GeneralError}
import zio._
import zio.openfeature.internal.ProviderRegistration
import zio.openfeature.internal.ProviderRegistration.Settled
import zio.test._

/** How a registration settles: decided by whether the provider's `initialize()` returned, not by the state it reports
  * afterwards.
  */
object ProviderRegistrationSpec extends ZIOSpecDefault {

  private class Provider(name: String, init: Provider => Unit) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = init(this)

    def reportError(message: String, fatal: Boolean = false): Unit = {
      val details = ProviderEventDetails.builder().message(message)
      val _       = emitProviderError(
        (if (fatal) details.errorCode(dev.openfeature.sdk.ErrorCode.PROVIDER_FATAL) else details).build()
      )
    }

    private def none[T](default: T): ProviderEvaluation[T] =
      zio.openfeature.internal.ProviderEvaluations.of[T](default, "DEFAULT")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = none(d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = none(d)
    override def getIntegerEvaluation(k: String, d: Integer, c: OFEvaluationContext)           = none(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = none(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = none(d)
  }

  private def stateOf(api: OpenFeatureAPI, domain: String): UIO[ProviderState] =
    ZIO.succeed(api.getClient(domain).getProviderState)

  private def until(api: OpenFeatureAPI, domain: String, state: ProviderState): Task[Unit] =
    stateOf(api, domain)
      .repeat(Schedule.spaced(5.millis) && Schedule.recurUntil[ProviderState](_ == state))
      .timeoutFail(new RuntimeException(s"the SDK never reported $state"))(5.seconds)
      .unit

  private def registered[A](f: (OpenFeatureAPI, String) => ZIO[Any, Any, A]): ZIO[Any, Any, A] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"registration-${java.util.UUID.randomUUID()}"
    f(api, domain).ensuring(ZIO.attemptBlocking(api.shutdown()).ignore)
  }

  def spec = suite("ProviderRegistrationSpec")(
    test("a provider without a name: an initialize() that returns, then an ERROR, settles as Error") {
      registered { (api, domain) =>
        val provider = new Provider(null, _ => ())
        ProviderRegistration
          .registerThenAwait(api, Some(domain), provider, 5.seconds)(
            until(api, domain, ProviderState.READY) *>
              ZIO.succeed(provider.reportError("lost the backend")) *>
              until(api, domain, ProviderState.ERROR)
          )
          .map(settled => assertTrue(settled == Settled.Error("lost the backend")))
      }
    },
    test("a provider without a name whose initialize() throws still fails the registration") {
      registered { (api, domain) =>
        val provider = new Provider(null, _ => throw new RuntimeException("bad config"))
        ProviderRegistration.registerAndAwait(api, Some(domain), provider, 5.seconds).either.map { result =>
          assertTrue(result.left.toOption.exists(_.getMessage.contains("bad config")))
        }
      }
    },
    test("over a slot already READY, a nameless provider whose initialize() throws is not counted as initialized") {
      // A watch opened before the registration is run at once for the READY slot, with no provider name: that run is
      // about the provider the slot held, and must not count for the new one.
      registered { (api, domain) =>
        val before = new Provider("before", _ => ())
        val after  = new Provider(null, _ => throw new RuntimeException("bad config"))
        for {
          _      <- ZIO.attemptBlocking(api.setProviderAndWait(domain, before))
          watch  <- ZIO.succeed(ProviderRegistration.watchReady(api, Some(domain), after))
          result <- ProviderRegistration
            .registerThenAwait(api, Some(domain), after, 5.seconds, Some(watch))(ZIO.unit)
            .either
            .ensuring(ZIO.succeed(watch.close()))
        } yield assertTrue(result.isLeft, !watch.initialized)
      }
    },
    test("an initialize() that returns, then an ERROR before the wait reads it, settles as Error") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => ())
        ProviderRegistration
          .registerThenAwait(api, Some(domain), provider, 5.seconds)(
            until(api, domain, ProviderState.READY) *>
              ZIO.succeed(provider.reportError("lost the backend")) *>
              until(api, domain, ProviderState.ERROR)
          )
          .map(settled => assertTrue(settled == Settled.Error("lost the backend")))
      }
    },
    test("the same with a FATAL settles as Fatal") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => ())
        ProviderRegistration
          .registerThenAwait(api, Some(domain), provider, 5.seconds)(
            until(api, domain, ProviderState.READY) *>
              ZIO.succeed(provider.reportError("revoked", fatal = true)) *>
              until(api, domain, ProviderState.FATAL)
          )
          .map(settled => assertTrue(settled == Settled.Fatal("revoked")))
      }
    },
    test("an initialize() that throws fails the registration with its message") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => throw new RuntimeException("bad config"))
        ProviderRegistration.registerAndAwait(api, Some(domain), provider, 5.seconds).either.map { result =>
          assertTrue(
            result.left.toOption.exists(e => e.isInstanceOf[GeneralError] && e.getMessage.contains("bad config"))
          )
        }
      }
    },
    test("an initialize() that throws a FatalError fails the registration with a FatalError") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => throw new FatalError("bad sdk key"))
        ProviderRegistration.registerAndAwait(api, Some(domain), provider, 5.seconds).either.map { result =>
          assertTrue(
            result.left.toOption.exists(e => e.isInstanceOf[FatalError] && e.getMessage.contains("bad sdk key"))
          )
        }
      }
    },
    test("an ERROR reported from inside an initialize() that then returns settles, never fails") {
      registered { (api, domain) =>
        val provider = new Provider("p", p => p.reportError("warming up"))
        ProviderRegistration.registerAndAwait(api, Some(domain), provider, 5.seconds).either.map { result =>
          assertTrue(result.isRight)
        }
      }
    },
    test("on the default slot, an initialize() that returns, then an ERROR, settles as Error too") {
      val api                                     = OpenFeatureAPI.createIsolated()
      val provider                                = new Provider("p", _ => ())
      def until(state: ProviderState): Task[Unit] =
        ZIO
          .succeed(api.getClient().getProviderState)
          .repeat(Schedule.spaced(5.millis) && Schedule.recurUntil[ProviderState](_ == state))
          .timeoutFail(new RuntimeException(s"the SDK never reported $state"))(5.seconds)
          .unit
      ProviderRegistration
        .registerThenAwait(api, None, provider, 5.seconds)(
          until(ProviderState.READY) *> ZIO.succeed(provider.reportError("lost the backend")) *>
            until(ProviderState.ERROR)
        )
        .map(settled => assertTrue(settled == Settled.Error("lost the backend")))
        .ensuring(ZIO.attemptBlocking(api.shutdown()).ignore)
    },
    test("a READY from a same-named provider bound before the registration does not count as initialized") {
      registered { (api, domain) =>
        val before = new Provider("same", _ => ())
        val after  = new Provider("same", _ => throw new RuntimeException("bad config"))
        for {
          _      <- ZIO.attemptBlocking(api.setProviderAndWait(domain, before))
          watch  <- ZIO.succeed(ProviderRegistration.watchReady(api, Some(domain), after))
          _      <- ZIO.succeed(before.emitProviderReady(ProviderEventDetails.builder().build()))
          _      <- ZIO.sleep(200.millis)
          result <- ProviderRegistration
            .registerThenAwait(api, Some(domain), after, 5.seconds, Some(watch))(ZIO.unit)
            .either
            .ensuring(ZIO.succeed(watch.close()))
        } yield assertTrue(result.isLeft, !watch.initialized)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
