package sangria.optics.agent

import org.scalatest.{Matchers, WordSpec}
import sangria.util.FileUtil

class OpticsQueryNormalizerSpec extends WordSpec with Matchers {

  "OpticsQueryNormalizer" should {
    "work for the apollo-provided example" in {
      val query = FileUtil.loadParsedQuery("optics-agent-signature-query.graphql")
      val normalizedQuery = OpticsQueryNormalizer.default.normalizeQuery(query)
      normalizedQuery should equal {
        """query Foo{user(id:""){name tz...Baz}}fragment Baz on User{dob}"""
      }
    }
  }
}
