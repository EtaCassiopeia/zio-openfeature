package snippets

// --8<-- [start:fixture-imports]
import rift.RiftError
import rift.bridge.InterceptConfig
import rift.zio.testkit.intercept as riftIntercept
import rift.zio.{InterceptHandle, Rift}
// --8<-- [end:fixture-imports]
import rift.dsl.*
import rift.model.TcpFaultKind
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import zio.*

/** The rift side of the "Testing real providers" page: one engine and one intercept proxy for the whole suite, and the
  * network faults the provider suites inject through it.
  */
object RiftFixture:

  // --8<-- [start:fixture]
  val layer: ZLayer[Any, RiftError, Rift & InterceptHandle] =
    ZLayer.scopedEnvironment {
      for
        engine <- Rift.embedded.build.map(_.get[Rift])
        ca     <- riftIntercept.caFromBuildProps()
        handle <- engine.intercept(InterceptConfig(ca = Some(ca)))
        _      <- riftIntercept.systemProxyProps(handle)
      yield ZEnvironment(engine, handle)
    }
  // --8<-- [end:fixture]

/** A provider factory's typed configuration error as the `Throwable` that `fromAcquireAsync`'s `acquire` expects. */
def acquired[A <: dev.openfeature.sdk.FeatureProvider](
  make: ZIO[Scope, zio.openfeature.FeatureFlagError, A]
): RIO[Scope, dev.openfeature.sdk.FeatureProvider] =
  make.mapError(e => new IllegalStateException(e.message))

/** What the network does to the provider's requests to one host. */
enum Fault:
  case Healthy, Unauthorized, Forbidden, Unavailable, RateLimited, Reset, Stalled, Truncated, NotJson

object Fault:

  private val jsonType = "application/json"

  /** How long a stalled response waits. Far beyond any bound the suites assert, so a provider that waits for it fails.
    */
  val stallFor: FiniteDuration = FiniteDuration(30, SECONDS)

  /** Point `host` at this fault, replacing whatever an earlier test installed. `healthy` is the valid payload for the
    * provider under test; the faults that still answer (`Stalled`, `Truncated`) are built from it.
    *
    * Plain answers are `serve` rules. A wait or a TCP fault needs an imposter, so those redirect to one.
    */
  def install(fault: Fault, host: String, healthy: String): ZIO[Rift & InterceptHandle, RiftError, Unit] =
    for
      engine <- ZIO.service[Rift]
      handle <- ZIO.service[InterceptHandle]
      _      <- handle.clearRules
      _      <- engine.deleteAll
      served = (response: IsResponseBuilder) => handle.rule(host).serve(response).unit
      imposterWith = (name: String, response: IsResponseBuilder) =>
        engine.create(imposter(name).stub(onRequest.reply(response))).flatMap(handle.rule(host).redirectTo(_)).unit
      _ <- fault match
        case Fault.Healthy => served(ok.header("Content-Type", jsonType).json(healthy))
        case Fault.Unauthorized =>
          served(status(401).header("Content-Type", jsonType).json("""{"error":"unauthorized"}"""))
        case Fault.Forbidden => served(status(403).header("Content-Type", jsonType).json("""{"error":"forbidden"}"""))
        case Fault.Unavailable =>
          served(status(503).header("Content-Type", jsonType).json("""{"error":"unavailable"}"""))
        case Fault.RateLimited =>
          served(
            status(429).header("Retry-After", "30").header("Content-Type", jsonType).json("""{"error":"slow down"}""")
          )
        case Fault.NotJson   => served(ok.header("Content-Type", jsonType).text("<html>captive portal</html>"))
        case Fault.Truncated => served(ok.header("Content-Type", jsonType).text(healthy.take(healthy.length / 2)))
        case Fault.Reset     => imposterWith("reset", ok.withTcpFault(TcpFaultKind.ConnectionResetByPeer))
        case Fault.Stalled =>
          imposterWith("stalled", ok.header("Content-Type", jsonType).json(healthy).after(stallFor))
    yield ()
