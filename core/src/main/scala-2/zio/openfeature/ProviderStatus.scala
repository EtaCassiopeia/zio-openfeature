package zio.openfeature

sealed trait ProviderStatus extends Product with Serializable {
  def canEvaluate: Boolean = this == ProviderStatus.Ready || this == ProviderStatus.Stale

  def isRecoverable: Boolean = this != ProviderStatus.Fatal
}

object ProviderStatus {
  case object NotReady     extends ProviderStatus
  case object Ready        extends ProviderStatus
  case object Error        extends ProviderStatus
  case object Stale        extends ProviderStatus
  case object Fatal        extends ProviderStatus
  case object ShuttingDown extends ProviderStatus
}
