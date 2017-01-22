package sangria.optics.agent

trait OpticsHttpClient {
  def postRequest(url: String, headers: Map[String, String], payload: String)
}
