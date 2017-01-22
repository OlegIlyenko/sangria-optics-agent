package sangria.optics.agent

import sangria.ast
import sangria.ast.Document

trait OpticsQueryNormalizer {
  def normalizeQuery(query: ast.Document): ast.Document
}

object OpticsQueryNormalizer {
  implicit val default = new OpticsQueryNormalizer {
    override def normalizeQuery(query: Document) = ???
  }
}
