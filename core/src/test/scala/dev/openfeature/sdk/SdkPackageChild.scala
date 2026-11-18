package dev.openfeature.sdk

import dev.openfeature.sdk.internal.{AutoCloseableReentrantReadWriteLock, TriConsumer}

/** An `EventProvider` that reaches the SDK's package-private `attach` and `detach`, so a test can run code at the
  * moment the chain attaches to or detaches from it.
  */
class SdkPackageChild(
  name: String,
  onInitialize: () => Unit = () => (),
  onDetach: () => Unit = () => (),
  attachFails: Boolean = false
) extends EventProvider {

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata { override def getName: String = name }

  override def initialize(ctx: EvaluationContext): Unit = onInitialize()

  // The SDK's shutdown terminates the emitter, and a test re-initializes and emits after the chain shuts this down.
  override def shutdown(): Unit = ()

  override private[sdk] def attach(
    onEmit: TriConsumer[EventProvider, ProviderEvent, ProviderEventDetails],
    lock: AutoCloseableReentrantReadWriteLock
  ): Unit =
    if (attachFails) throw new RuntimeException("attach failed") else super.attach(onEmit, lock)

  override private[sdk] def detach(): Unit = {
    onDetach()
    super.detach()
  }

  def emitStale(): Unit =
    emitProviderStale(ProviderEventDetails.builder().message("stale").build()).await()

  override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: EvaluationContext) =
    new ProviderEvaluation[java.lang.Boolean](d, null, "DEFAULT", null, null, null)
  override def getStringEvaluation(k: String, d: String, c: EvaluationContext) =
    new ProviderEvaluation[String](d, null, "DEFAULT", null, null, null)
  override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: EvaluationContext) =
    new ProviderEvaluation[java.lang.Integer](d, null, "DEFAULT", null, null, null)
  override def getDoubleEvaluation(k: String, d: java.lang.Double, c: EvaluationContext) =
    new ProviderEvaluation[java.lang.Double](d, null, "DEFAULT", null, null, null)
  override def getObjectEvaluation(k: String, d: Value, c: EvaluationContext) =
    new ProviderEvaluation[Value](d, null, "DEFAULT", null, null, null)
}
