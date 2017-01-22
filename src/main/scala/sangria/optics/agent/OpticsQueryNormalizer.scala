package sangria.optics.agent

import sangria.ast

trait OpticsQueryNormalizer {
  def normalizeQuery(query: ast.Document): ast.Document
}
