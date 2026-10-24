package zio.openfeature.testkit

import scala.jdk.CollectionConverters._

import zio.{Chunk, Task, ZIO}
import zio.test.{Assertion, Spec, TestAspect, assert, suite, test}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  ImmutableContext,
  ImmutableStructure,
  MutableTrackingEventDetails,
  TrackingEventDetails => OFTracking,
  Value
}

/** Law checks for a provider wrapper: a `FeatureProvider` that decorates another one (caching, circuit breaking,
  * deferred construction, ...).
  *
  * The OpenFeature SDK adds capabilities as '''default methods''' on `FeatureProvider`. A wrapper written against an
  * older SDK keeps compiling, and silently answers the new method from the interface default instead of asking the
  * provider it wraps: a dropped `track`, a `getLongEvaluation` routed through doubles, an `initialize(ctx, domain)`
  * that loses the domain. Reflection on the wrapper's declared methods cannot see this reliably; calling each method
  * and watching what reaches the wrapped provider can.
  *
  * [[delegation]] wraps a [[FaultyProvider]] (which records every call with its arguments) and, for every [[Op]], calls
  * that method on the wrapper with distinctive arguments and checks that the wrapped provider received the same call
  * with equal arguments. Contexts are compared by targeting key and `asMap`, tracking details by value and `asMap`,
  * everything else with `==`. Results are not checked: the law is about delegation, not about what the wrapper does
  * with the answer.
  *
  * {{{
  * object MyWrapperSpec extends ZIOSpecDefault {
  *   def spec = WrapperLaws.delegation("MyWrapper")(
  *     inner => new MyWrapper(inner),
  *     handledByWrapper = Map(Op.GetMetadata -> "reports its own name")
  *   )
  * }
  * }}}
  *
  * This lives in the shared source tree, so it runs on Scala 2.13 and 3 alike.
  */
object WrapperLaws {

  /** One test per [[Op]], each on a fresh wrapper around a fresh [[FaultyProvider]].
    *
    *   - An op not in `handledByWrapper` must reach the wrapped provider with equal arguments. Its test is named "NAME
    *     delegates OP". For `Op.Initialize` both overloads are checked, each on its own fresh wrapper. For
    *     `Op.GetMetadata` the law reads `getMetadata.getName`, since a wrapper may ask for the wrapped provider's name
    *     only then.
    *   - An op in `handledByWrapper` must NOT reach it: the wrapper answers by itself. Its test is named "NAME handles
    *     OP itself: REASON". If the op is delegated after all, the test fails with "allow-list entry no longer needed",
    *     so the list cannot go stale. An exception the wrapper throws for such an op is ignored: rejecting a call is
    *     one way of handling it.
    *
    * Every op but `Op.Initialize` is called on a wrapper initialized first, with a context and a domain, as the SDK
    * would; the calls that initialization makes are not counted. Each wrapper is shut down when its test ends (except
    * in the `Op.Shutdown` test, which shuts it down itself).
    *
    * @param name
    *   the wrapper's name, used in the test names
    * @param wrap
    *   builds the wrapper around the provider it must delegate to
    * @param handledByWrapper
    *   the ops the wrapper deliberately answers itself, each with a one-line reason
    */
  def delegation(name: String)(
    wrap: FeatureProvider => FeatureProvider,
    handledByWrapper: Map[Op, String] = Map.empty
  ): Spec[Any, Throwable] =
    suite(s"$name delegation")(Op.values.map(op => law(name, wrap, op, handledByWrapper.get(op)))) @@
      TestAspect.withLiveClock

  // Distinctive arguments, so a wrapper that substitutes its own (a fresh context, a constant default) is caught.
  private val context: OFEvaluationContext =
    new ImmutableContext(
      "wrapper-law-targeting-key",
      Map[String, Value]("wrapper-law-attribute" -> new Value("wrapper-law-attribute-value")).asJava
    )
  private val domain    = "wrapper-law-domain"
  private val eventName = "wrapper-law-event"
  private def details: OFTracking =
    new MutableTrackingEventDetails(java.lang.Double.valueOf(42.5))
      .add("wrapper-law-detail", "wrapper-law-detail-value")
  private val objectDefault =
    new Value(new ImmutableStructure(Map[String, Value]("wrapper-law-field" -> new Value(7)).asJava))

  /** One call of the method under test: what to call on the wrapper, and the arguments the delegate must receive. */
  final private case class Invocation(
    description: String,
    primed: Boolean,
    call: FeatureProvider => Any,
    expected: Chunk[Any]
  )

  private def primed(description: String, expected: Any*)(call: FeatureProvider => Any): Chunk[Invocation] =
    Chunk(Invocation(description, primed = true, call, Chunk.fromIterable(expected)))

  @scala.annotation.nowarn("msg=deprecated")
  private def invocations(op: Op): Chunk[Invocation] = op match {
    case Op.GetMetadata      => primed("getMetadata.getName")(_.getMetadata.getName)
    case Op.GetProviderHooks => primed("getProviderHooks")(_.getProviderHooks)
    case Op.GetState         => primed("getState")(_.getState)
    case Op.Initialize =>
      Chunk(
        Invocation("initialize(ctx)", primed = false, _.initialize(context), Chunk(context)),
        Invocation("initialize(ctx, domain)", primed = false, _.initialize(context, domain), Chunk(context, domain))
      )
    case Op.Shutdown       => primed("shutdown")(_.shutdown())
    case Op.IsDomainScoped => primed("isDomainScoped")(_.isDomainScoped())
    case Op.Track =>
      val d = details
      primed("track", eventName, context, d)(_.track(eventName, context, d))
    case Op.EvaluateBoolean =>
      val (key, default) = ("wrapper-law-boolean", java.lang.Boolean.TRUE)
      primed("getBooleanEvaluation", key, default, context)(_.getBooleanEvaluation(key, default, context))
    case Op.EvaluateString =>
      val (key, default) = ("wrapper-law-string", "wrapper-law-default")
      primed("getStringEvaluation", key, default, context)(_.getStringEvaluation(key, default, context))
    case Op.EvaluateInteger =>
      val (key, default) = ("wrapper-law-integer", java.lang.Integer.valueOf(4242))
      primed("getIntegerEvaluation", key, default, context)(_.getIntegerEvaluation(key, default, context))
    case Op.EvaluateLong =>
      val (key, default) = ("wrapper-law-long", java.lang.Long.valueOf(42424242L))
      primed("getLongEvaluation", key, default, context)(_.getLongEvaluation(key, default, context))
    case Op.EvaluateDouble =>
      val (key, default) = ("wrapper-law-double", java.lang.Double.valueOf(42.4242))
      primed("getDoubleEvaluation", key, default, context)(_.getDoubleEvaluation(key, default, context))
    case Op.EvaluateObject =>
      val key = "wrapper-law-object"
      primed("getObjectEvaluation", key, objectDefault, context)(_.getObjectEvaluation(key, objectDefault, context))
  }

  /** The SDK values compared structurally; a wrapper may pass an equal copy rather than the same instance. */
  private def comparable(argument: Any): Any = argument match {
    case d: OFTracking          => ("TrackingEventDetails", d.getValue, d.asMap)
    case c: OFEvaluationContext => ("EvaluationContext", c.getTargetingKey, c.asMap)
    case other                  => other
  }

  private def show(arguments: Seq[Any]): String =
    arguments.map(comparable).mkString("(", ", ", ")")

  /** What reached the delegate during one invocation, and whether the wrapper threw. */
  final private case class Observed(
    invocation: Invocation,
    calls: Chunk[FaultyProvider.Call],
    error: Option[Throwable]
  )

  private def observe(wrap: FeatureProvider => FeatureProvider, op: Op, invocation: Invocation) =
    ZIO.scoped {
      for {
        recorder <- FaultyProvider.scoped()
        wrapper <- ZIO.acquireRelease(ZIO.attempt(wrap(recorder))) { w =>
          if (op == Op.Shutdown) ZIO.unit else ZIO.attemptBlocking(w.shutdown()).orDie
        }
        _      <- ZIO.attemptBlocking(wrapper.initialize(context, domain)).when(invocation.primed)
        before <- recorder.calls.map(_.size)
        result <- ZIO.attemptBlocking(invocation.call(wrapper)).either
        calls  <- recorder.calls.map(_.drop(before).filter(_.op == op))
      } yield Observed(invocation, calls, result.left.toOption)
    }

  /** The problems with one observation; empty when it satisfies the law. */
  private def problems(name: String, handled: Boolean, observed: Observed): Option[String] = {
    val what     = observed.invocation.description
    val expected = observed.invocation.expected.map(comparable)
    val threw    = observed.error.fold("")(e => s" (the wrapper threw $e)")
    if (handled)
      if (observed.calls.isEmpty) None
      else Some(s"allow-list entry no longer needed: $name delegates $what")
    else if (observed.calls.exists(_.arguments.map(comparable) == expected)) None
    else if (observed.calls.isEmpty)
      Some(s"$name does not delegate $what: the wrapped provider received no call$threw")
    else
      Some(
        s"$name delegates $what with different arguments: expected ${show(observed.invocation.expected)}, " +
          s"received ${observed.calls.map(c => show(c.arguments)).mkString(" and ")}$threw"
      )
  }

  private def law(
    name: String,
    wrap: FeatureProvider => FeatureProvider,
    op: Op,
    handled: Option[String]
  ): Spec[Any, Throwable] = {
    val label = handled.fold(s"$name delegates $op")(reason => s"$name handles $op itself: $reason")
    test(label)(violations(name, wrap, op, handled.isDefined).map(found => assert(found)(Assertion.isEmpty)))
  }

  /** Why `op` breaks the law for the wrapper `wrap` builds; empty when it holds. */
  private[testkit] def violations(
    name: String,
    wrap: FeatureProvider => FeatureProvider,
    op: Op,
    handled: Boolean
  ): Task[Chunk[String]] =
    ZIO.foreach(invocations(op))(observe(wrap, op, _)).map(_.flatMap(problems(name, handled, _)))
}
