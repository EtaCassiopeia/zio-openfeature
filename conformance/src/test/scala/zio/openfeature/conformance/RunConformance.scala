package zio.openfeature.conformance

import io.cucumber.junit.{Cucumber, CucumberOptions}
import org.junit.runner.RunWith

/** JUnit entry point: sbt's junit-interface discovers this `@RunWith(Cucumber)` class and Cucumber executes every
  * `.feature` on the classpath under this package. Tag filters mirror the OpenFeature Java SDK's own e2e runner.
  */
@RunWith(classOf[Cucumber])
@CucumberOptions(
  features = Array("classpath:zio/openfeature/conformance"),
  glue = Array("zio.openfeature.conformance"),
  plugin = Array("pretty"),
  tags = "not @deprecated and not @reason-codes-cached and not @async and not @evaluation-options and not @immutability"
)
class RunConformance
