package sangria.optics.agent

import language.postfixOps

import org.slf4j.LoggerFactory

import scala.concurrent.duration._

case class OpticsConfig(
  apiKey: String,
  endpointUrl: String = OpticsConfig.DefaultEndpointUrl,
  reportInterval: FiniteDuration = OpticsConfig.DefaultReportInterval,
  schemaReportDelay: FiniteDuration = OpticsConfig.DefaultSchemaReportDelay,
  enabled: Boolean = OpticsConfig.DefaultEnabled,
  reportTraces: Boolean = OpticsConfig.DefaultReportTraces,
  reportVariables: Boolean = OpticsConfig.DefaultReportVariables
) {
  import OpticsConfig._

  def withEnv: Unit = {
    val newApiKey = sys.env.getOrElse(EnvVars.ApiKey, apiKey)
    val newEndpointUrl = sys.env.getOrElse(EnvVars.EndpointUrl, endpointUrl)
    val newEnabled = sys.env.get(EnvVars.Enabled) map (_.toBoolean) getOrElse enabled
    val newReportInterval = sys.env.get(EnvVars.ReportIntervalMs) map (_.toLong milliseconds) getOrElse reportInterval
    val newSchemaReportDelay = sys.env.get(EnvVars.SchemaReportDelayMs) map (_.toLong milliseconds) getOrElse schemaReportDelay
    val newReportTraces = sys.env.get(EnvVars.ReportTraces) map (_.toBoolean) getOrElse reportTraces
    val newReportVariables = sys.env.get(EnvVars.ReportVariables) map (_.toBoolean) getOrElse reportVariables

    OpticsConfig(newApiKey, newEndpointUrl, newReportInterval, newSchemaReportDelay, newEnabled, newReportTraces, reportVariables)
  }
}

object OpticsConfig {
  val logger = LoggerFactory.getLogger(this.getClass)

  def fromEnv = sys.env.get(EnvVars.ApiKey) match {
    case Some(apiKey) ⇒
      val endpointUrl = sys.env.getOrElse(EnvVars.EndpointUrl, DefaultEndpointUrl)
      val enabled = sys.env.get(EnvVars.Enabled) map (_.toBoolean) getOrElse DefaultEnabled
      val reportInterval = sys.env.get(EnvVars.ReportIntervalMs) map (_.toLong milliseconds) getOrElse DefaultReportInterval
      val schemaReportDelay = sys.env.get(EnvVars.SchemaReportDelayMs) map (_.toLong milliseconds) getOrElse DefaultSchemaReportDelay
      val reportTraces = sys.env.get(EnvVars.ReportTraces) map (_.toBoolean) getOrElse DefaultReportTraces
      val reportVariables = sys.env.get(EnvVars.ReportVariables) map (_.toBoolean) getOrElse DefaultReportVariables

      OpticsConfig(apiKey, endpointUrl, reportInterval, schemaReportDelay, enabled, reportTraces, reportVariables)

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
  val DefaultReportTraces = true
  val DefaultReportVariables = true

  object EnvVars {
    val ApiKey = "OPTICS_API_KEY"
    val EndpointUrl = "OPTICS_ENDPOINT_URL"
    val Enabled = "OPTICS_ENABLED"
    val ReportIntervalMs = "OPTICS_REPORT_INTERVAL_MS"
    val SchemaReportDelayMs = "OPTICS_SCHEMA_REPORT_DELAY_MS"
    val ReportTraces = "OPTICS_REPORT_TRACES"
    val ReportVariables = "OPTICS_REPORT_VARIABLES"
  }
}
