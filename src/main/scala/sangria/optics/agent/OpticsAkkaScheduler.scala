package sangria.optics.agent
import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.control.NonFatal

class OpticsAkkaScheduler(system: ActorSystem) extends OpticsScheduler {
  import system.dispatcher

  private val logger = LoggerFactory.getLogger(classOf[OpticsAkkaScheduler])

  override def scheduleOnce(delay: FiniteDuration, fn: () => Future[Unit]): Unit =
    system.scheduler.scheduleOnce(delay) {
      try {
        fn().onComplete {
          case Failure(e) ⇒ handleException(e)
          case _ ⇒ // everything is fine
        }
      } catch {
        case NonFatal(e) ⇒ handleException(e)
      }
    }

  private def handleException(e: Throwable) = {
    logger.error("Unhandled exception in timer-based scheduler task.", e)
  }
}

object OpticsAkkaScheduler {
  implicit def scheduler(implicit system: ActorSystem) = new OpticsAkkaScheduler(system)
}
