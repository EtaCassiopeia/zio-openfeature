package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.{OfrepProvider, OfrepProviderOptions}

/** Scala-friendly factories for the OpenFeature Java SDK's OFREP contrib provider
  * ([[dev.openfeature.contrib.providers.ofrep.OfrepProvider]]).
  *
  * OFREP (OpenFeature Remote Evaluation Protocol) is the standard HTTP protocol for vendor-neutral remote flag
  * evaluation. The factories here just sugar over the contrib provider's static constructors; the returned value is a
  * plain `OfrepProvider` (a `FeatureProvider`) that you pass to `FeatureFlags.fromProvider` or
  * `FeatureFlags.fromProviderAsync` like any other provider.
  *
  * Note: the underlying contrib provider artifact is at version 0.0.1 — the API may evolve as OFREP itself matures.
  *
  * @see
  *   https://github.com/open-feature/protocol for the OFREP spec
  * @see
  *   https://github.com/open-feature/java-sdk-contrib/tree/main/providers/ofrep for the underlying implementation
  */
object OFREPProvider {

  /** Create an OFREP provider with the contrib provider's default options (defaults to `http://localhost:8016`) or
    * pointed at a specific endpoint.
    */
  def apply(baseUrl: String = "http://localhost:8016"): OfrepProvider =
    OfrepProvider.constructProvider(OfrepProviderOptions.builder().baseUrl(baseUrl).build())

  /** Create an OFREP provider with a fully configured [[OfrepProviderOptions]] (auth headers, timeouts, executor,
    * etc.).
    */
  def fromOptions(options: OfrepProviderOptions): OfrepProvider =
    OfrepProvider.constructProvider(options)
}
