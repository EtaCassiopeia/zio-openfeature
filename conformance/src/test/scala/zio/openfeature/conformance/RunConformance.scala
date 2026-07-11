package zio.openfeature.conformance

import io.cucumber.junit.{Cucumber, CucumberOptions}
import org.junit.runner.RunWith

/** JUnit entry point: sbt's junit-interface discovers this `@RunWith(Cucumber)` class and Cucumber executes every
  * `.feature` on the classpath under this package.
  *
  * The excluded tags must stay in sync with the zio-bdd runner's `excludeTags`; the canonical rationale for each
  * intentional exclusion (`@deprecated`, `@async`, `@immutability`) lives in `ConformanceSpec` in the
  * `conformance-zio-bdd` module. Both runners exclude exactly {deprecated, async, immutability}.
  */
@RunWith(classOf[Cucumber])
@CucumberOptions(
  features = Array("classpath:zio/openfeature/conformance"),
  glue = Array("zio.openfeature.conformance"),
  plugin = Array("pretty"),
  tags = "not @deprecated and not @async and not @immutability"
)
class RunConformance
