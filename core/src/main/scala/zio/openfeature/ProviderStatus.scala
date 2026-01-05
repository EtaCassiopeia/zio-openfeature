package zio.openfeature

enum ProviderStatus:
  case NotReady
  case Ready
  case Error
  case Stale
  case ShuttingDown

  def canEvaluate: Boolean = this == Ready || this == Stale
