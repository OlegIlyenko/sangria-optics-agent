package sangria.optics.agent

import java.util.concurrent.Executor

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

trait OpticsScheduler {
  def scheduleOnce(delay: FiniteDuration, fn: () ⇒ Future[Unit])
}

object OpticsScheduler {
  implicit def default = new OpticsTimerScheduler

  implicit val syncExecutionContext = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) = command.run()
  })
}
