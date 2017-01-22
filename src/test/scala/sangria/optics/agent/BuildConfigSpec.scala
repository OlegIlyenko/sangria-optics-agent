package sangria.optics.agent

import org.scalatest.{Matchers, WordSpec}

class BuildConfigSpec extends WordSpec with Matchers {

  "BuildConfig" should {
    "find appropriate config file in the classpath" in {
      BuildConfig.default.name should include ("sangria")
    }
  }

}
