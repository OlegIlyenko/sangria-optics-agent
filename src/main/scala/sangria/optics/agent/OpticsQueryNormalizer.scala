package sangria.optics.agent

import sangria.ast
import sangria.ast.Document
import sangria.renderer.QueryRenderer

trait OpticsQueryNormalizer {
  def normalizeQuery(query: ast.Document): String
}

object OpticsQueryNormalizer {
  implicit val default = new OpticsQueryNormalizer {
    override def normalizeQuery(query: Document) =
      QueryRenderer.render(query, QueryRenderer.Compact) // TODO: use the optics normalisation spec
  }
}
