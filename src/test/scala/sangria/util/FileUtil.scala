package sangria.util

import sangria.ast.Document
import sangria.parser.QueryParser
import sangria.parser.DeliveryScheme.Throw

import scala.io.Source

// TODO: Dedupe from Sangria
object FileUtil {
  def loadQuery(name: String) =
    loadResource("queries/" + name)

  def loadParsedQuery(name: String): Document =
    QueryParser.parse(loadQuery(name))

  def loadSchema(path: String) =
    QueryParser.parse(loadResource(path))

  def loadResource(path: String) =
    Option(this.getClass.getResourceAsStream("/" + path)) match {
      case Some(res) ⇒ Source.fromInputStream(res, "UTF-8").mkString
      case None ⇒ throw new IllegalArgumentException("Resource not found: /" + path)
    }
}
