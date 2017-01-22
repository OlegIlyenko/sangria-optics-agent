package sangria.optics.agent

case class OpticsAgent(config: OpticsConfig)(implicit scheduler: OpticsScheduler, httpClient: OpticsHttpClient, queryNormalizer: OpticsQueryNormalizer) {

}
