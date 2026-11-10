package dev.openfeature.sdk

/** An isolated API that runs `beforeClient` ahead of every `getClient(domain)`, so a test can hold a caller just before
  * it reads a slot. It lives in the SDK's package because `OpenFeatureAPI`'s constructor is package-private.
  */
final class HeldClientApi(beforeClient: String => Unit) extends OpenFeatureAPI {
  override def getClient(domain: String): Client = {
    beforeClient(domain)
    super.getClient(domain)
  }
}
