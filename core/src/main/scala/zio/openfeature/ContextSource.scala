package zio.openfeature

import zio.UIO

/** A pull-based source of ambient evaluation context, consulted once per evaluation (#353).
  *
  * The library already covers the '''push''' direction: `withContext` and the fiber-local context carry identity down
  * into a scope. A `ContextSource` covers the '''pull''' direction — "consult this effect for ambient context at
  * evaluation time" — which is what an application carrying request identity outside the ZIO environment needs: an
  * MDC-style map, a tracing/tag manager, a correlation-id carrier.
  *
  * {{{
  * val fromMdc = ContextSource(ZIO.succeed(EvaluationContext(Mdc.get("userId"))))
  *
  * FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withContextSource(fromMdc))
  * }}}
  *
  * ==Precedence==
  *
  * The source is merged into the effective context at a fixed slot:
  *
  * {{{
  * global -> transaction -> client -> contextSource -> fiberLocal -> invocation
  * }}}
  *
  * So ambient request identity '''overrides''' static client and global context, while an explicit `withContext` or a
  * per-call context at the call site still '''wins''' over it. That ordering is the reason this belongs in the library
  * rather than in a `before` hook: a hook's contribution is merged on top of the finished effective context, so it can
  * only ever occupy the highest-precedence slot, and it cannot reconstruct the ordering either — `HookContext`'s
  * context is a single flattened value with no record of which attribute came from the client, the fiber or the call
  * site. The slot this needs is reachable only from inside the merge itself.
  *
  * ==Failure==
  *
  * `current` returns `UIO`, so a source can never fail an evaluation. A source with nothing to contribute returns
  * [[EvaluationContext.empty]], which merges to a no-op. Anything that might fail must be handled inside the source.
  *
  * The source is consulted on every evaluation and on `track`, so keep `current` cheap — read a `FiberRef` or a
  * `ThreadLocal`, do not call a network service.
  */
trait ContextSource { self =>

  /** The ambient context as of now. */
  def current: UIO[EvaluationContext]

  /** Compose two sources; `that` wins on key collisions, matching the right-biased merge used everywhere else.
    *
    * A method on the trait rather than a Scala 3 `extension`, because this file is cross-compiled.
    */
  def ++(that: ContextSource): ContextSource =
    new ContextSource {
      def current: UIO[EvaluationContext] =
        self.current.zipWith(that.current)(_.merge(_))
    }
}

object ContextSource {

  /** Contributes nothing. The default, and the identity of [[ContextSource.++]]. */
  val empty: ContextSource = new ContextSource {
    def current: UIO[EvaluationContext] = zio.ZIO.succeed(EvaluationContext.empty)
  }

  /** Build a source from an effect. The effect is by-name, so it is re-evaluated per evaluation rather than captured
    * once — which is the whole point of a pull-based source.
    */
  def apply(f: => UIO[EvaluationContext]): ContextSource = new ContextSource {
    def current: UIO[EvaluationContext] = f
  }
}
