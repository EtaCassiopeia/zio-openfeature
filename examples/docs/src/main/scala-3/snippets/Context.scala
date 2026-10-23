package snippets

import java.time.Instant

import zio.*
import zio.openfeature.*

/** Code shown on the Evaluation context page. */
object Context:

  // --8<-- [start:create]
  // A targeting key only
  val user: EvaluationContext = EvaluationContext("user-123")

  // ...plus attributes. Plain Scala values convert to AttributeValue on their own.
  val premiumUser: EvaluationContext =
    EvaluationContext("user-123")
      .withAttribute("plan", "premium")
      .withAttribute("age", 30)
      .withAttribute("beta", true)

  // The builder reads well when there are many attributes
  val built: EvaluationContext =
    EvaluationContext.builder
      .targetingKey("user-123")
      .attribute("plan", "premium")
      .attribute("score", 4.5)
      .build

  // Sets the targeting key and two attributes: entityId = "org-7", entityType = "organization"
  val org: EvaluationContext = EvaluationContext.forEntity("org-7", "organization")
  // --8<-- [end:create]

  // --8<-- [start:attribute-types]
  val rich: EvaluationContext =
    EvaluationContext("user-123").withAttributes(
      "beta"     -> AttributeValue.bool(true),
      "plan"     -> AttributeValue.string("premium"),
      "seats"    -> AttributeValue.int(25),
      "orgId"    -> AttributeValue.long(9007199254740993L), // see the note on longs below
      "score"    -> AttributeValue.double(4.5),
      "signedUp" -> AttributeValue.instant(Instant.parse("2024-01-15T10:00:00Z")),
      "roles"    -> AttributeValue.list(AttributeValue.string("admin"), AttributeValue.string("editor")),
      "address" -> AttributeValue.struct(
        "country" -> AttributeValue.string("NL"),
        "city"    -> AttributeValue.string("Amsterdam")
      )
    )
  // --8<-- [end:attribute-types]

  // --8<-- [start:read]
  val plan: Option[String]        = premiumUser.getString("plan") // Some("premium")
  val age: Option[Int]            = premiumUser.getInt("age")     // Some(30)
  val raw: Option[AttributeValue] = premiumUser.get("beta")       // Some(BoolValue(true))
  // --8<-- [end:read]

  // --8<-- [start:levels]
  val levels: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    for
      // Global: the whole application, every FeatureFlags client
      _ <- FeatureFlags.setGlobalContext(EvaluationContext.empty.withAttribute("env", "production"))
      // Client: this FeatureFlags instance
      _ <- FeatureFlags.setClientContext(EvaluationContext.empty.withAttribute("service", "checkout"))
      // Scoped: everything inside the block, on this fiber and fibers it forks
      enabled <- FeatureFlags.withContext(EvaluationContext("user-123").withAttribute("plan", "free")) {
        // Invocation: this one call
        FeatureFlags.boolean("new-ui", false, EvaluationContext.empty.withAttribute("plan", "premium"))
      }
    yield enabled
  // The provider sees: targetingKey = user-123, env = production, service = checkout, plan = premium
  // --8<-- [end:levels]

  // --8<-- [start:merge]
  val base: EvaluationContext =
    EvaluationContext("user-1")
      .withAttribute("plan", "free")
      .withAttribute(
        "address",
        AttributeValue.struct("country" -> AttributeValue.string("NL"), "city" -> AttributeValue.string("Amsterdam"))
      )

  val overrides: EvaluationContext =
    EvaluationContext.empty
      .withAttribute("plan", "premium")
      .withAttribute("address", AttributeValue.struct("city" -> AttributeValue.string("Utrecht")))

  // The right-hand side wins key by key; structs merge field by field.
  // targetingKey = user-1, plan = premium, address = {country = NL, city = Utrecht}
  val merged: EvaluationContext = base.merge(overrides)
  // --8<-- [end:merge]

  // --8<-- [start:context-source]
  // Identity the application already carries as log annotations, pulled in on every evaluation
  val fromLogAnnotations: ContextSource =
    ContextSource(
      ZIO.logAnnotations.map { annotations =>
        annotations.get("userId").fold(EvaluationContext.empty)(EvaluationContext(_))
      }
    )

  val fromRegion: ContextSource =
    ContextSource(ZIO.succeed(EvaluationContext.empty.withAttribute("region", "eu-west-1")))

  // Combine sources with ++; the right-hand one wins on a key both set
  val config: FeatureFlagsConfig =
    FeatureFlagsConfig().withContextSource(fromRegion ++ fromLogAnnotations)

  // Anything logged-annotated with a userId is now targeted at that user, with no context passed by hand
  val handleRequest: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.logAnnotate("userId", "user-42") {
      FeatureFlags.boolean("new-ui", false)
    }
  // --8<-- [end:context-source]

  // --8<-- [start:request-handler]
  final case class Request(userId: String, country: String, plan: String)

  def handle(request: Request): ZIO[FeatureFlags, FeatureFlagError, String] =
    val ctx = EvaluationContext(request.userId)
      .withAttribute("country", request.country)
      .withAttribute("plan", request.plan)
    FeatureFlags.withContext(ctx) {
      for
        newUi  <- FeatureFlags.boolean("new-ui", false)
        banner <- FeatureFlags.string("banner", "none")
      yield s"newUi=$newUi banner=$banner"
    }
  // --8<-- [end:request-handler]
