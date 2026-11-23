package dev.openfeature.sdk

import java.util.concurrent.ThreadPoolExecutor

/** Test-only reads of the SDK's provider executor, by reflection: the fields are private, so no shim can reach them.
  * The registry replay uses it to let the SDK's queued work run before a close (`OpenFeatureAPI.shutdown()` racing a
  * swap's queued `shutDownOld`, an SDK behaviour pinned by `SdkLateShutdownCanarySpec`).
  */
object SdkExecutorAccess {

  /** How many tasks the API's provider executor has taken and not finished: queued, or running. Read through the
    * repository current at the call, as `OpenFeatureAPI.shutdown()` replaces it.
    */
  def pending(api: OpenFeatureAPI): Long = {
    val repository = field(classOf[OpenFeatureAPI], "providerRepository").get(api)
    val executor   = field(classOf[ProviderRepository], "taskExecutor").get(repository).asInstanceOf[ThreadPoolExecutor]
    executor.getTaskCount - executor.getCompletedTaskCount
  }

  private def field(owner: Class[_], name: String): java.lang.reflect.Field = {
    val f = owner.getDeclaredField(name)
    f.setAccessible(true)
    f
  }
}
