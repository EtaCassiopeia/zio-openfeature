package zio.openfeature.internal

import java.util.{ArrayList, Collections, HashMap}
import java.util.function.{BiFunction, Function => JFunction}

import dev.openfeature.sdk.{
  ClientMetadata,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  FlagEvaluationDetails,
  FlagValueType,
  Hook => OFHook,
  HookContext => OFHookContext,
  MultiProviderHookExecutor,
  ProviderEvaluation,
  TrackingEventDetails => OFTrackingEventDetails
}
import dev.openfeature.sdk.multiprovider.{MultiProvider, Strategy}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** The SDK's `MultiProvider` with a `getLongEvaluation` that runs the strategy over each child's own long getter
  * (#533).
  *
  * The SDK's `MultiProvider` (1.23.0) overrides the boolean, string, integer, double and object getters but not the
  * long one, so a long read falls to the interface default, which goes through `getDoubleEvaluation`: each child's
  * double getter, inexact past 2^53 and a `TYPE_MISMATCH` for integer values in every provider this library ships.
  *
  * This subclass adds the missing getter with public SDK API only, mirroring what the SDK does for the other five
  * types: a provider hook records the caller's client metadata and hints in a thread-local (`before`) and clears it
  * (`finallyAfter`), and each child is evaluated through `MultiProviderHookExecutor`, so the child's own provider hooks
  * run and see `FlagValueType.LONG` and the caller's client. It also forwards `track` to every child, which the SDK's
  * `MultiProvider` drops. Initialization, shutdown, events, metadata and the other five getters are inherited
  * unchanged.
  */
final private[openfeature] class LongAwareMultiProvider(
  providers: java.util.List[OFFeatureProvider],
  strategy: Strategy
) extends MultiProvider(providers, strategy) {

  import LongAwareMultiProvider._

  // The same defensive copy the super constructor makes; its own copy is private.
  private val children: java.util.List[OFFeatureProvider] =
    Collections.unmodifiableList(new ArrayList[OFFeatureProvider](providers))

  private val chainStrategy: Strategy = strategy

  private val hookExecutor = new MultiProviderHookExecutor()

  private val captured = new ThreadLocal[Captured]()

  private val captureHook: OFHook[Object] = new OFHook[Object] {
    override def before(
      ctx: OFHookContext[Object],
      hints: java.util.Map[String, Object]
    ): java.util.Optional[OFEvaluationContext] = {
      captured.set(Captured(ctx.getClientMetadata, snapshot(hints)))
      java.util.Optional.empty[OFEvaluationContext]()
    }
    override def finallyAfter(
      ctx: OFHookContext[Object],
      details: FlagEvaluationDetails[Object],
      hints: java.util.Map[String, Object]
    ): Unit = captured.remove()
  }

  private val hooks: java.util.List[OFHook[_]] = {
    val all = new ArrayList[OFHook[_]](super.getProviderHooks)
    all.add(captureHook)
    Collections.unmodifiableList(all)
  }

  override def getProviderHooks: java.util.List[OFHook[_]] = hooks

  // The SDK's MultiProvider inherits FeatureProvider's no-op `track`, so the event would reach no child. Tracking has no
  // result for a strategy to pick, so every child is offered it, and one throwing child must not starve the rest: the
  // first failure is rethrown once all have run, carrying the others as suppressed.
  override def track(eventName: String, ctx: OFEvaluationContext, details: OFTrackingEventDetails): Unit = {
    val failures = children.asScala.toList.flatMap(child => Try(child.track(eventName, ctx, details)).failed.toOption)
    failures.headOption.foreach { first =>
      // Children may rethrow shared or cached instances: a Throwable cannot suppress itself, and one already suppressed
      // by an earlier call must not be added again, or a cached exception's list grows with every event.
      failures.tail
        .filterNot(f => (f eq first) || first.getSuppressed.exists(_ eq f))
        .distinct
        .foreach(first.addSuppressed)
      throw first
    }
  }

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] = {
    // Read on the calling thread, before the strategy runs, as the SDK's own getters do. Nothing captured means a
    // direct provider call with no client: fall back to the metadata and hints the SDK uses on that path.
    val current  = Option(captured.get())
    val metadata = current.fold(fallbackMetadata)(_.metadata)
    val hints    = current.fold(emptyHints)(_.hints)
    val callLong = new BiFunction[OFFeatureProvider, OFEvaluationContext, ProviderEvaluation[java.lang.Long]] {
      def apply(p: OFFeatureProvider, c: OFEvaluationContext): ProviderEvaluation[java.lang.Long] =
        p.getLongEvaluation(key, defaultValue, c)
    }
    chainStrategy.evaluate[java.lang.Long](
      children,
      key,
      defaultValue,
      ctx,
      new JFunction[OFFeatureProvider, ProviderEvaluation[java.lang.Long]] {
        def apply(child: OFFeatureProvider): ProviderEvaluation[java.lang.Long] =
          hookExecutor.execute[java.lang.Long](
            child,
            key,
            defaultValue,
            FlagValueType.LONG,
            ctx,
            metadata,
            hints,
            callLong
          )
      }
    )
  }
}

private[openfeature] object LongAwareMultiProvider {

  final private case class Captured(metadata: ClientMetadata, hints: java.util.Map[String, Object])

  private val emptyHints: java.util.Map[String, Object] = Collections.emptyMap[String, Object]()

  // The SDK's MultiProvider hands its children a client whose domain is its own name when nothing was captured.
  private val fallbackMetadata: ClientMetadata = new ClientMetadata {
    def getDomain: String = MultiProvider.getNAME
  }

  // An immutable copy that tolerates null keys and values, which `Map.copyOf` rejects.
  private def snapshot(hints: java.util.Map[String, Object]): java.util.Map[String, Object] =
    if (hints == null || hints.isEmpty) emptyHints
    else Collections.unmodifiableMap(new HashMap[String, Object](hints))
}
