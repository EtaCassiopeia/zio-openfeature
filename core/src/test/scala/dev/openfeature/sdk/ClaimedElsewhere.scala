package dev.openfeature.sdk

/** Another API's claim on a provider, for a test that needs the SDK to refuse a binding before it binds anything.
  *
  * The SDK records which API each registered provider belongs to, and refuses any other API's registration of it with
  * an `IllegalStateException`. A claim made here registers the provider with a fresh API of its own without binding or
  * initializing it, so the refused binding changes nothing, and a stale attachment (which the library resets) is not
  * what refuses it.
  */
object ClaimedElsewhere {

  /** Claim `provider` for a fresh API, and return that API, for [[release]]. */
  def claim(provider: FeatureProvider): OpenFeatureAPI = {
    val other = OpenFeatureAPI.createIsolated()
    other.registerGlobalProvider(provider)
    other
  }

  /** Drop the claim [[claim]] made. */
  def release(api: OpenFeatureAPI, provider: FeatureProvider): Unit = api.deregisterGlobalProvider(provider)
}
