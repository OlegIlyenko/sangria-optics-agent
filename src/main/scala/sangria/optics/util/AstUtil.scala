package sangria.optics.util

import sangria.ast

object AstUtil {
  // TODO: move in sangria
  def removeUnusedOperations(doc: ast.Document, operationName: Option[String]): ast.Document = doc // TODO: isolate
}
