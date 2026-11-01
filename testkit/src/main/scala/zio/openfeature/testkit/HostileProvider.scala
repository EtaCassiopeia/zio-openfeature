package zio.openfeature.testkit

import zio._
import zio.test.Gen
import dev.openfeature.sdk.ProviderEvent

/** Providers that answer wrongly, for testing that a library on top of them never dies.
  *
  * A [[HostileProvider.Script]] assigns each [[Op]] of a [[FaultyProvider]] a list of [[Fault]]s. The generators draw
  * them from the shapes real providers have produced: a malformed answer ([[AnswerShape]]), malformed metadata
  * ([[MetadataShape]]), a thrown checked exception, unchecked exception or `Error` ([[HostileProvider.throwables]]), an
  * event emitted from inside `initialize()`, an `initialize()` that only returns at shutdown, and a `shutdown()` that
  * throws.
  *
  * {{{
  * check(HostileProvider.evaluationScripts) { script =>
  *   ZIO.scoped {
  *     for {
  *       provider <- HostileProvider.scripted(script, Map("flag" -> true))
  *       // build a FeatureFlags over `provider` and assert that no evaluation dies
  *     } yield assertCompletes
  *   }
  * }
  * }}}
  */
object HostileProvider {

  /** The faults per op; an op with no entry delegates. */
  type Script = Map[Op, Chunk[Fault]]

  /** An `Error` that is not a `VirtualMachineError`: what a provider's own assertion failure looks like. */
  final class HostileError(message: String) extends Error(message)

  /** One checked exception, one unchecked exception and one non-fatal `Error`. */
  val throwables: Chunk[Throwable] = Chunk(
    new java.io.IOException("hostile provider: checked"),
    new IllegalStateException("hostile provider: unchecked"),
    new HostileError("hostile provider: error")
  )

  /** The six evaluation ops. */
  val evaluationOps: Chunk[Op] = Chunk(
    Op.EvaluateBoolean,
    Op.EvaluateString,
    Op.EvaluateInteger,
    Op.EvaluateLong,
    Op.EvaluateDouble,
    Op.EvaluateObject
  )

  /** A malformed answer or a thrown [[throwables]] entry. */
  val evaluationFault: Gen[Any, Fault] =
    Gen.elements[Fault]((AnswerShape.values.map(Fault.Answer(_)) ++ throwables.map(Fault.Throw(_))): _*)

  /** Malformed metadata, or the provider's own. */
  val metadataFault: Gen[Any, Fault] =
    Gen.elements[Fault]((MetadataShape.values.map(Fault.MetadataAnswer(_)) :+ Fault.Succeed): _*)

  /** `READY` emitted from inside `initialize()`, an `initialize()` that returns only at shutdown, or a clean one. */
  val initializeFault: Gen[Any, Fault] =
    Gen.elements[Fault](
      Fault.Emit(ProviderEvent.PROVIDER_READY),
      Fault.HangUntilShutdown,
      Fault.Succeed
    )

  /** A `shutdown()` that throws one of [[throwables]], or a clean one. */
  val shutdownFault: Gen[Any, Fault] =
    Gen.elements[Fault]((throwables.map(Fault.Throw(_)) :+ Fault.Succeed): _*)

  /** One fault for every evaluation op and one for `getMetadata`, so a provider that has started cleanly answers each
    * evaluation wrongly.
    */
  val evaluationScripts: Gen[Any, Script] =
    for {
      faults   <- Gen.collectAll(evaluationOps.map(op => evaluationFault.map(op -> _)))
      metadata <- metadataFault
    } yield (faults :+ (Op.GetMetadata -> metadata)).map { case (op, f) => op -> Chunk(f) }.toMap

  /** [[evaluationScripts]] plus a hostile `initialize()` and `shutdown()`. */
  val scripts: Gen[Any, Script] =
    for {
      evaluation <- evaluationScripts
      init       <- initializeFault
      shutdown   <- shutdownFault
    } yield evaluation ++ Map(Op.Initialize -> Chunk(init), Op.Shutdown -> Chunk(shutdown))

  /** A [[FaultyProvider.scoped]] over a [[TestFeatureProvider]] holding `flags`, with `script` applied. */
  def scripted(
    script: Script,
    flags: Map[String, Any] = Map.empty,
    name: String = FaultyProvider.DefaultName
  ): URIO[Scope, FaultyProvider[TestFeatureProvider]] =
    for {
      provider <- FaultyProvider.scoped(flags, name)
      _        <- ZIO.foreachDiscard(script) { case (op, faults) => provider.script(op, faults: _*) }
    } yield provider
}
