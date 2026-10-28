package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object ContextSpec extends ZIOSpecDefault:

  private def lastContext: ZIO[TestFeatureProvider, Nothing, EvaluationContext] =
    ZIO.serviceWithZIO[TestFeatureProvider](_.getEvaluations).map(_.last._2)

  private val str = AttributeValue.string

  def spec = suite("Context")(
    test("create: constructors set what the page says") {
      assertTrue(
        Context.user.targetingKey.contains("user-123"),
        Context.premiumUser.getString("plan").contains("premium"),
        Context.premiumUser.getInt("age").contains(30),
        Context.premiumUser.getBoolean("beta").contains(true),
        Context.built.getDouble("score").contains(4.5),
        Context.org.targetingKey.contains("org-7"),
        Context.org.getString("entityId").contains("org-7"),
        Context.org.getString("entityType").contains("organization"),
        Context.rich.attributes.size == 8,
        Context.plan.contains("premium"),
        Context.age.contains(30),
        Context.raw.contains(AttributeValue.BoolValue(true))
      )
    },
    test("merge: right wins, structs merge field by field") {
      val m = Context.merged
      assertTrue(
        m.targetingKey.contains("user-1"),
        m.getString("plan").contains("premium"),
        m.get("address").contains(AttributeValue.struct("country" -> str("NL"), "city" -> str("Utrecht")))
      )
    },
    test("levels: the provider sees every level merged, the invocation winning") {
      for
        _   <- Context.levels
        ctx <- lastContext
      yield assertTrue(
        ctx.targetingKey.contains("user-123"),
        ctx.getString("env").contains("production"),
        ctx.getString("service").contains("checkout"),
        ctx.getString("plan").contains("premium")
      )
    }.provide(TestFeatureProvider.layer(Map("new-ui" -> true))),
    test("precedence: global < transaction < client < source < scoped < invocation < before hook") {
      def layer(key: String, value: String) = EvaluationContext.empty.withAttribute(key, value)
      val source = ContextSource(ZIO.succeed(layer("source", "source").withAttribute("p", "source")))
      for
        provider <- TestFeatureProvider.make(Map("f" -> true))
        domain = s"docs-${java.util.UUID.randomUUID()}"
        env <- FeatureFlags
          .fromProvider(provider, FeatureFlagsConfig().withDomain(domain).withContextSource(source))
          .build
        ff = env.get[FeatureFlags]
        _ <- ff.setGlobalContext(layer("p", "global").withAttribute("global", "global"))
        _ <- ff.setClientContext(layer("p", "client").withAttribute("client", "client"))
        read = (ctx: EvaluationContext) => ff.boolean("f", false, ctx) *> provider.getEvaluations.map(_.last._2)
        inTx = (z: ZIO[Any, FeatureFlagError, EvaluationContext]) =>
          ff.transaction(context = layer("p", "tx").withAttribute("tx", "tx"))(z).map(_.result)
        // client beats transaction
        a <- inTx(read(EvaluationContext.empty))
        // scoped beats source; invocation beats scoped
        b <- ff.withContext(layer("p", "scoped"))(read(EvaluationContext.empty))
        c <- ff.withContext(layer("p", "scoped"))(read(layer("p", "invocation")))
        // a before hook beats the invocation
        _ <- ff.addHook(Hooks.stampRegion("hooked"))
        d <- read(layer("region", "invocation"))
      yield assertTrue(
        a.getString("p").contains("source"),
        a.getString("tx").contains("tx"),
        a.getString("global").contains("global"),
        b.getString("p").contains("scoped"),
        c.getString("p").contains("invocation"),
        d.getString("region").contains("hooked")
      )
    },
    test("transaction context ranks below client context") {
      for
        provider <- TestFeatureProvider.make(Map("f" -> true))
        domain = s"docs-${java.util.UUID.randomUUID()}"
        env <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain(domain)).build
        ff = env.get[FeatureFlags]
        _ <- ff.setGlobalContext(EvaluationContext.empty.withAttribute("p", "global"))
        _ <- ff.transaction(context = EvaluationContext.empty.withAttribute("p", "tx"))(ff.boolean("f", false))
        t <- provider.getEvaluations.map(_.last._2)
        _ <- ff.setClientContext(EvaluationContext.empty.withAttribute("p", "client"))
        _ <- ff.transaction(context = EvaluationContext.empty.withAttribute("p", "tx"))(ff.boolean("f", false))
        c <- provider.getEvaluations.map(_.last._2)
      yield assertTrue(t.getString("p").contains("tx"), c.getString("p").contains("client"))
    },
    test("nested withContext merges with the outer scope") {
      for
        _ <- FeatureFlags.withContext(EvaluationContext("u").withAttribute("a", "outer").withAttribute("b", "outer")) {
          FeatureFlags.withContext(EvaluationContext.empty.withAttribute("b", "inner")) {
            FeatureFlags.boolean("new-ui", false)
          }
        }
        ctx <- lastContext
      yield assertTrue(
        ctx.targetingKey.contains("u"),
        ctx.getString("a").contains("outer"),
        ctx.getString("b").contains("inner")
      )
    }.provide(TestFeatureProvider.layer(Map("new-ui" -> true))),
    test("context source: log annotations become the targeting key, ++ composes") {
      for
        provider <- TestFeatureProvider.make(Map("new-ui" -> true))
        domain = s"docs-${java.util.UUID.randomUUID()}"
        env <- FeatureFlags.fromProvider(provider, Context.config.withDomain(domain)).build
        _   <- Context.handleRequest.provideEnvironment(env)
        ctx <- provider.getEvaluations.map(_.last._2)
      yield assertTrue(ctx.targetingKey.contains("user-42"), ctx.getString("region").contains("eu-west-1"))
    }
  ) @@ TestAspect.withLiveClock
