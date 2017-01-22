package sangria.optics.agent

import language.postfixOps

import org.slf4j.LoggerFactory

import scala.concurrent.duration._

case class OpticsConfig(
  apiKey: String,
  endpointUrl: String = OpticsConfig.DefaultEndpointUrl,
  reportInterval: FiniteDuration = OpticsConfig.DefaultReportInterval,
  enabled: Boolean = OpticsConfig.DefaultEnabled
) {
  import OpticsConfig._

  def withEnv: Unit = {
    val newApiKey = sys.props.get(EnvVars.ApiKey) getOrElse apiKey
    val newEndpointUrl = sys.props.get(EnvVars.EndpointUrl) getOrElse endpointUrl
    val newEnabled = sys.props.get(EnvVars.ApiKey) map (_.toBoolean) getOrElse enabled
    val newReportInterval = sys.props.get(EnvVars.ApiKey) map (_.toLong milliseconds) getOrElse reportInterval

    OpticsConfig(newApiKey, newEndpointUrl, newReportInterval, newEnabled)
  }
}

object OpticsConfig {
  val logger = LoggerFactory.getLogger(this.getClass)

  def fromEnv = sys.props.get(EnvVars.ApiKey) match {
    case Some(apiKey) ⇒
      val endpointUrl = sys.props.get(EnvVars.EndpointUrl) getOrElse DefaultEndpointUrl
      val enabled = sys.props.get(EnvVars.ApiKey) map (_.toBoolean) getOrElse DefaultEnabled
      val reportInterval = sys.props.get(EnvVars.ApiKey) map (_.toLong milliseconds) getOrElse DefaultReportInterval

      OpticsConfig(apiKey, endpointUrl, reportInterval, enabled)

    case None ⇒
      logger.warn(
        "Optics agent disabled: no API key specified. Set the `apiKey` option to `OpticsConfig`, " +
        s"or set the `${EnvVars.ApiKey}` environment variable.")

      OpticsConfig("no-api-key", enabled = false)
  }

  val MinReportInterval = 10 seconds

  val DefaultReportInterval = 1 minute
  val DefaultEndpointUrl = "https://optics-report.apollodata.com"
  val DefaultEnabled = true

  object EnvVars {
    val ApiKey = "OPTICS_API_KEY"
    val EndpointUrl = "OPTICS_ENDPOINT_URL"
    val Enabled = "OPTICS_ENABLED"
    val ReportIntervalMs = "OPTICS_REPORT_INTERVAL_MS"
  }
}
