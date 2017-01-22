package sangria.optics.agent

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import org.slf4j.LoggerFactory
import sangria.execution.Executor
import sangria.schema.Schema

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

case class OpticsAgent[Ctx](schema: Schema[Ctx, _], userContext: Ctx = (), config: OpticsConfig = OpticsConfig.fromEnv)(implicit scheduler: OpticsScheduler, httpClient: OpticsHttpClient, queryNormalizer: OpticsQueryNormalizer, reporter: Reporter, ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(classOf[OpticsAgent[Ctx]])

  private val enabled = new AtomicBoolean(config.enabled)

  // Data we've collected so far this report period.
  private val pendingResults = new AtomicReference[String]("")

  // The wall clock time for the beginning of the current report period.
  private val reportStartTime = new AtomicLong(System.currentTimeMillis())

  // The HR clock time for the beginning of the current report
  // period. We record this so we can get an accurate duration for
  // the report even when the wall clock shifts or drifts.
  private val reportStartHrTime = new AtomicLong(System.nanoTime())

  private val types = Reporter.typesFromSchema(schema)

  def enable() =
    if (!enabled.get) {
      config.apiKey match {
        case OpticsConfig.NotProvidedApiKey ⇒ // API key is invalid, can't enable
        case _ ⇒
          logger.debug("Optics reporter is enabled")

          enabled.set(true)
      }
    }

  def disable() =
    if (enabled.get) {
      if (logger.isDebugEnabled) logger.debug("Optics reporter is disabled")

      enabled.set(false)
    }

  private[sangria] def scheduleStatsReport(): Unit =
    if (enabled.get())
      scheduler.scheduleOnce(config.reportInterval, () ⇒ sendStatsReport())

  private[sangria] def sendStatsReport(): Future[Unit] =
    if (enabled.get()) {
      val currTime = System.currentTimeMillis()
      val currHrTime = System.nanoTime()

      val reportData = pendingResults.getAndSet("")
      val oldStartTime = reportStartTime.getAndSet(currTime)
      val oldStartHrTime = reportStartHrTime.getAndSet(currHrTime)

      val durationHr = currHrTime - oldStartHrTime

      reporter.sendStatsReport(types, reportData, oldStartTime, currTime, durationHr, httpClient, config).recover {
        case NonFatal(e) ⇒
          logger.error("Something went wrong during optics metrics reporting", e)

          ()
      }
    } else {
      Future.successful(())
    }

  // Sent once on startup. Wait 10 seconds to report the schema. This
  // does two things:
  // - help apps start up and serve users faster. don't clog startup
  //   time with reporting.
  // - avoid sending a ton of reports from a crash-looping server.
  private[sangria] def scheduleSchemaReport() =
    if (enabled.get())
      scheduler.scheduleOnce(config.schemaReportDelay, () ⇒ sendSchemaReport())

  private[sangria] def sendSchemaReport(): Future[Unit] =
    if (enabled.get()) {
      import sangria.marshalling.circe._

      Executor.execute(schema.asInstanceOf[Schema[Ctx, Any]], Reporter.opticsIntrospectionQuery, userContext = userContext).flatMap { introspection ⇒
        val schemaIntrospection =
          for {
            root ← introspection.asObject
            data ← root("data") flatMap (_.asObject)
            schema ← data("__schema")
          } yield schema.noSpaces

        schemaIntrospection match {
          case Some(s) ⇒ reporter.sendSchemaReport(types, s, httpClient, config)
          case None ⇒
            Future.failed(new IllegalStateException("Introspection results have wrong shape"))
        }
      }.recover {
        case NonFatal(e) ⇒
          logger.error("Something went wrong during optics initial schema reporting", e)

          ()
      }
    } else {
      Future.successful(())
    }

  scheduleStatsReport()
}