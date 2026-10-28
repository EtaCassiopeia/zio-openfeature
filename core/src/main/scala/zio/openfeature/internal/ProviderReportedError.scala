package zio.openfeature.internal

/** The cause carried by a typed error built from an error CODE a provider returned, where no exception exists.
  * Stackless: a stack trace would only point into this library's own result decoding, not at the provider's problem.
  */
final private[openfeature] class ProviderReportedError(message: String)
    extends RuntimeException(message, null, false, false)
