package sangria.optics.agent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

trait OpticsScheduler {
  def scheduleOnce(delay: FiniteDuration, fn: () ⇒ Future[Unit])
  def scheduleRequestProcessing(fn: () ⇒ Future[Unit])
}

object OpticsScheduler {
  implicit def default = new OpticsTimerScheduler(ThreadPoolConfig(
    corePoolSize = 1, maximumPoolSize = 3, queueSize = 300, handler = new ThreadPoolExecutor.DiscardOldestPolicy))

  def defaultRequestProcessingThreadPool(threadPoolConfig: ThreadPoolConfig) = ExecutionContext.fromExecutor(
    new ThreadPoolExecutor(threadPoolConfig.corePoolSize, threadPoolConfig.maximumPoolSize,
      30, TimeUnit.SECONDS,
      new ArrayBlockingQueue[Runnable](threadPoolConfig.queueSize),
      namedThreadFactory("optics-request-processing"), threadPoolConfig.handler))

  private def namedThreadFactory(poolName: String): ThreadFactory =
    new ThreadFactory {
      val count = new AtomicInteger(0)

      override def newThread(r: Runnable) = {
        val thread = new Thread(r, poolName + "-" + count.incrementAndGet)

        thread.setDaemon(true)

        thread
      }
    }


  implicit val syncExecutionContext = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) = command.run()
  })
}

case class ThreadPoolConfig(corePoolSize: Int, maximumPoolSize: Int, queueSize: Int, handler: RejectedExecutionHandler)