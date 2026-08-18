package zio.openfeature.conformance.bdd.library

import zio._
import zio.bdd.core.step.ZIOSteps
import zio.openfeature._
// zio-bdd's Hooks trait defines `type FeatureHook = URIO[R, Unit]`, which shadows the OpenFeature hook trait; alias it.
import zio.openfeature.FeatureHook as OFFeatureHook
import zio.openfeature.extras.{EnvVarProvider, HoconProvider, IntegerWideningLongProvider}
import zio.openfeature.testkit.TestFeatureProvider
import zio.openfeature.testkit.FlagOverride.Ops
import zio.schema.{DeriveSchema, Schema}
import com.typesafe.config.ConfigFactory
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}

/** Row type for seeding an object-valued flag from a gherkin table. */
final case class FieldRow(key: String, value: String, value_type: String)

object FieldRow {
  given Schema[FieldRow] = DeriveSchema.gen[FieldRow]
}

/** Provider construction, flag seeding, context and hook registration. */
trait LibrarySetupSteps { self: ZIOSteps[Any, LibraryWorld] =>

  // Provider construction -------------------------------------------------------------------------

  Given("a test provider") {
    for {
      sc <- Scope.make
      tp <- TestFeatureProvider.make
      ff <- sc.extend[Any](LibraryHarness.build(tp))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), testProvider = Some(tp), flags = Some(ff)))
    } yield ()
  }

  Given("a test provider named " / string) { (name: String) =>
    for {
      sc <- Scope.make
      tp <- TestFeatureProvider.makeNamed(name)
      ff <- sc.extend[Any](LibraryHarness.build(tp))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), testProvider = Some(tp), flags = Some(ff)))
    } yield ()
  }

  Given("a test provider seeded with the typed override Plan set to " / string) { (tier: String) =>
    for {
      sc <- Scope.make
      tp <- TestFeatureProvider.make(Flags.Plan := Tier.valueOf(tier))
      ff <- sc.extend[Any](LibraryHarness.build(tp))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), testProvider = Some(tp), flags = Some(ff)))
    } yield ()
  }

  Given("a test provider seeded with the typed override MaxItems set to " / int) { (n: Int) =>
    for {
      sc <- Scope.make
      tp <- TestFeatureProvider.make(Flags.MaxItems := Level(n))
      ff <- sc.extend[Any](LibraryHarness.build(tp))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), testProvider = Some(tp), flags = Some(ff)))
    } yield ()
  }

  Given("a test provider with an ambient context source carrying " / string / " = " / string) {
    (key: String, value: String) =>
      for {
        sc  <- Scope.make
        ref <- Ref.make(EvaluationContext.builder.attribute(key, value).build)
        tp  <- TestFeatureProvider.make(Map[String, Any]("kill.switch" -> true))
        ff <- sc.extend[Any](
          LibraryHarness.build(tp, FeatureFlagsConfig().withContextSource(ContextSource(ref.get)))
        )
        _ <- ScenarioContext.update(
          _.copy(scope = Some(sc), testProvider = Some(tp), flags = Some(ff), ambient = Some(ref))
        )
      } yield ()
  }

  Given("a flagless provider with fallback logging " / string) { (policy: String) =>
    for {
      sc <- Scope.make
      ff <- sc.extend[Any](
        LibraryHarness.build(
          new LibraryProviders.EmptyProvider("flagless"),
          FeatureFlagsConfig().withFallbackLogging(LibraryHarness.fallbackPolicy(policy))
        )
      )
      _ <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff), logSink = Some(new LogSink)))
    } yield ()
  }

  Given("a provider that returns a null string") {
    for {
      sc <- Scope.make
      ff <- sc.extend[Any](LibraryHarness.build(new LibraryProviders.NullStringProvider))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff)))
    } yield ()
  }

  Given("a provider that records the defaults it is handed") {
    for {
      sc <- Scope.make
      p = new LibraryProviders.DefaultRecordingProvider
      ff <- sc.extend[Any](LibraryHarness.build(p))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff), recorder = Some(p)))
    } yield ()
  }

  Given("a legacy provider without native long support") {
    for {
      sc <- Scope.make
      ff <- sc.extend[Any](LibraryHarness.build(new LibraryProviders.LegacyLongProvider))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff)))
    } yield ()
  }

  Given("a legacy provider wrapped for integer widening") {
    for {
      sc <- Scope.make
      ff <- sc.extend[Any](LibraryHarness.build(IntegerWideningLongProvider(new LibraryProviders.LegacyLongProvider)))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff)))
    } yield ()
  }

  Given("a HOCON provider configured with " / string) { (hocon: String) =>
    for {
      sc <- Scope.make
      ff <- sc.extend[Any](LibraryHarness.build(HoconProvider.fromConfig(ConfigFactory.parseString(hocon))))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff)))
    } yield ()
  }

  Given("an environment-variable provider holding " / string / " = " / string) { (name: String, value: String) =>
    for {
      sc <- Scope.make
      ff <- sc.extend[Any](
        LibraryHarness.build(EnvVarProvider.withLookup(k => if (k == name) Some(value) else None))
      )
      _ <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff)))
    } yield ()
  }

  Given(
    "a chain of a " / string / " provider and a " / string / " provider, only the second holding the boolean flag " / string
  ) { (first: String, second: String, key: String) =>
    for {
      sc <- Scope.make
      p1 <- TestFeatureProvider.makeNamed(first)
      p2 <- TestFeatureProvider.makeNamed(second, Map[String, Any](key -> true))
      ff <- sc.extend[Any](LibraryHarness.build(FeatureFlags.multiProvider(List[OFFeatureProvider](p1, p2))))
      _  <- ScenarioContext.update(_.copy(scope = Some(sc), flags = Some(ff), chainedProviders = List(p1, p2)))
    } yield ()
  }

  // Flag seeding ----------------------------------------------------------------------------------

  Given("the provider holds the string flag " / string / " with value " / string) { (key: String, value: String) =>
    ScenarioContext.get.flatMap(_.provider.setFlag(key, value))
  }

  Given("the provider holds the boolean flag " / string / " with value " / string) { (key: String, value: String) =>
    ScenarioContext.get.flatMap(_.provider.setFlag(key, value.toBoolean))
  }

  Given("the provider holds the integer flag " / string / " with value " / int) { (key: String, value: Int) =>
    ScenarioContext.get.flatMap(_.provider.setFlag(key, value))
  }

  Given("the provider holds the long flag " / string / " with value " / long) { (key: String, value: Long) =>
    ScenarioContext.get.flatMap(_.provider.setFlag(key, value))
  }

  Given("the provider holds the object flag " / string / " with fields" / table[FieldRow]) {
    (key: String, rows: List[FieldRow]) =>
      val fields = rows.map { row =>
        val v: Any = row.value_type.toLowerCase match {
          case "integer" => row.value.toInt
          case "long"    => row.value.toLong
          case "boolean" => row.value.toBoolean
          case "double"  => row.value.toDouble
          case _         => row.value
        }
        row.key -> v
      }.toMap
      ScenarioContext.get.flatMap(_.provider.setFlag(key, fields))
  }

  // Context ---------------------------------------------------------------------------------------

  Given("the global context carries " / string / " = " / string) { (key: String, value: String) =>
    ScenarioContext.get.flatMap(_.ff.setGlobalContext(EvaluationContext.builder.attribute(key, value).build))
  }

  Given("the client context carries " / string / " = " / string) { (key: String, value: String) =>
    ScenarioContext.get.flatMap(_.ff.setClientContext(EvaluationContext.builder.attribute(key, value).build))
  }

  Given("the ambient context source is updated to carry " / string / " = " / string) { (key: String, value: String) =>
    ScenarioContext.get.flatMap { w =>
      ZIO.foreachDiscard(w.ambient)(_.set(EvaluationContext.builder.attribute(key, value).build))
    }
  }

  // Hooks -----------------------------------------------------------------------------------------

  Given("a hook scoped to the " / string / " flag value type") { (typeName: String) =>
    for {
      w    <- ScenarioContext.get
      seen <- Ref.make(Chunk.empty[FlagValueType])
      hook = new OFFeatureHook {
        override def supportedFlagTypes: Set[FlagValueType] = Set(LibraryHarness.flagValueType(typeName))
        override def finallyAfter(
          ctx: HookContext,
          details: Option[FlagResolution[_]],
          hints: HookHints
        ): UIO[Unit] = seen.update(_ :+ ctx.flagType)
      }
      _ <- w.ff.addHook(hook)
      _ <- ScenarioContext.update(_.copy(hookSeen = Some(seen)))
    } yield ()
  }
}
