package sangria.optics.agent

import java.util.{Timer, TimerTask}

import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.control.NonFatal

class OpticsTimerScheduler extends OpticsScheduler {
  lazy val timer = new Timer("optics-agent-reporter-timer", true)

  def scheduleOnce(delay: FiniteDuration, fn: () ⇒ Future[Unit]) = {
    val task = new OpticsTimerScheduler.OpticsTask(fn)

    timer.schedule(task, delay.toMillis)
  }
}

object OpticsTimerScheduler {
  private class OpticsTask(fn: () ⇒ Future[Unit]) extends TimerTask {
    import OpticsScheduler.syncExecutionContext

    private val logger = LoggerFactory.getLogger(classOf[OpticsTask])

    private def handleException(e: Throwable) = {
      logger.error("Unhandled exception in timer-based scheduler task.", e)
    }

    override def run(): Unit = {
      try {
        fn().onComplete {
          case Failure(e) ⇒ handleException(e)
          case _ ⇒ // everything is fine
        }
      } catch {
        case NonFatal(e) ⇒ handleException(e)
      }
    }
  }
}
