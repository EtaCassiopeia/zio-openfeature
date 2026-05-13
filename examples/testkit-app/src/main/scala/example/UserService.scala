package example

import zio._
import zio.openfeature._

/** A trivial application service whose behaviour is gated by a feature flag. Used by `UserServiceSpec` to show how to
  * test flag-driven code against `TestFeatureProvider` without touching a real provider.
  */
trait UserService {
  def welcome(userId: String): IO[FeatureFlagError, String]
}

object UserService {
  val live: ZLayer[FeatureFlags, Nothing, UserService] =
    ZLayer.fromFunction((ff: FeatureFlags) =>
      new UserService {
        def welcome(userId: String): IO[FeatureFlagError, String] = {
          val ctx = EvaluationContext(userId)
          for {
            useNewGreeting <- ff.boolean("new-greeting-copy", default = false, ctx)
            greeting = if (useNewGreeting) s"Hey $userId 👋 — glad to have you back!" else s"Welcome, $userId."
          } yield greeting
        }
      }
    )
}
