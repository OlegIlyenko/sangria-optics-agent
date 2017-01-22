package sangria.optics.agent

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

case class OpticsAgent(config: OpticsConfig)(implicit scheduler: OpticsScheduler, httpClient: OpticsHttpClient, queryNormalizer: OpticsQueryNormalizer, reporter: Reporter) {
  val logger = LoggerFactory.getLogger(classOf[OpticsAgent])

  private val enabled = new AtomicBoolean(config.enabled)

  // Data we've collected so far this report period.
  private val pendingResults = new AtomicReference[String]("")

  // The wall clock time for the beginning of the current report period.
  private val reportStartTime = new AtomicLong(System.currentTimeMillis())

  // The HR clock time for the beginning of the current report
  // period. We record this so we can get an accurate duration for
  // the report even when the wall clock shifts or drifts.
  private val reportStartHrTime = new AtomicLong(System.nanoTime())

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

  private[sangria] def schedule(): Unit = {
    if (enabled.get())
      scheduler.scheduleOnce(config.reportInterval, () ⇒ sendStatsReport())
  }

  private[sangria] def sendStatsReport(): Future[Unit] =
    if (enabled.get()) {
      val currTime = System.currentTimeMillis()
      val currHrTime = System.nanoTime()

      val reportData = pendingResults.getAndSet("")
      val oldStartTime = reportStartTime.getAndSet(currTime)
      val oldStartHrTime = reportStartHrTime.getAndSet(currHrTime)

      val durationHr = currHrTime - oldStartHrTime

      reporter.sendStatsReport(reportData, oldStartTime, currTime, durationHr).recover {
        case NonFatal(e) ⇒
          logger.error("Something went wring during optics metrics reporting", e)

          ()
      }(OpticsScheduler.syncExecutionContext)
    } else {
      Future.successful(())
    }

  // TODO: maybe do it later
  sendStatsReport()
}