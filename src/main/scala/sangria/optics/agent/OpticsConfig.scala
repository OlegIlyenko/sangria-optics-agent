package sangria.optics.agent

import language.postfixOps

import org.slf4j.LoggerFactory

import scala.concurrent.duration._

case class OpticsConfig(
  apiKey: String,
  endpointUrl: String = OpticsConfig.DefaultEndpointUrl,
  reportInterval: FiniteDuration = OpticsConfig.DefaultReportInterval,
  schemaReportDelay: FiniteDuration = OpticsConfig.DefaultSchemaReportDelay,
  enabled: Boolean = OpticsConfig.DefaultEnabled
) {
  import OpticsConfig._

  def withEnv: Unit = {
    val newApiKey = sys.env.get(EnvVars.ApiKey) getOrElse apiKey
    val newEndpointUrl = sys.env.get(EnvVars.EndpointUrl) getOrElse endpointUrl
    val newEnabled = sys.env.get(EnvVars.Enabled) map (_.toBoolean) getOrElse enabled
    val newReportInterval = sys.env.get(EnvVars.ReportIntervalMs) map (_.toLong milliseconds) getOrElse reportInterval
    val newSchemaReportDelay = sys.env.get(EnvVars.SchemaReportDelayMs) map (_.toLong milliseconds) getOrElse schemaReportDelay

    OpticsConfig(newApiKey, newEndpointUrl, newReportInterval, newSchemaReportDelay, newEnabled)
  }
}

object OpticsConfig {
  val logger = LoggerFactory.getLogger(this.getClass)

  def fromEnv = sys.env.get(EnvVars.ApiKey) match {
    case Some(apiKey) ⇒
      val endpointUrl = sys.env.get(EnvVars.EndpointUrl) getOrElse DefaultEndpointUrl
      val enabled = sys.env.get(EnvVars.Enabled) map (_.toBoolean) getOrElse DefaultEnabled
      val reportInterval = sys.env.get(EnvVars.ReportIntervalMs) map (_.toLong milliseconds) getOrElse DefaultReportInterval
      val schemaReportDelay = sys.env.get(EnvVars.SchemaReportDelayMs) map (_.toLong milliseconds) getOrElse DefaultSchemaReportDelay

      OpticsConfig(apiKey, endpointUrl, reportInterval, schemaReportDelay, enabled)

    case None ⇒
      logger.warn(
        "Optics agent disabled: no API key specified. Set the `apiKey` option to `OpticsConfig`, " +
        s"or set the `${EnvVars.ApiKey}` environment variable.")

      OpticsConfig(NotProvidedApiKey, enabled = false)
  }

  val NotProvidedApiKey = "no-api-key"

  val MinReportInterval = 10 seconds

  val DefaultReportInterval = 1 minute
  val DefaultSchemaReportDelay = 10 seconds

  val DefaultEndpointUrl = "https://optics-report.apollodata.com"
  val DefaultEnabled = true

  object EnvVars {
    val ApiKey = "OPTICS_API_KEY"
    val EndpointUrl = "OPTICS_ENDPOINT_URL"
    val Enabled = "OPTICS_ENABLED"
    val ReportIntervalMs = "OPTICS_REPORT_INTERVAL_MS"
    val SchemaReportDelayMs = "OPTICS_SCHEMA_REPORT_DELAY_MS"
  }
}
