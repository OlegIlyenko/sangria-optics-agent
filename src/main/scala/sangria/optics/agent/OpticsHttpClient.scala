package sangria.optics.agent

import scala.concurrent.Future

trait OpticsHttpClient {
  def postRequest(url: String, headers: Map[String, String], payload: Array[Byte]): Future[Unit]
}
