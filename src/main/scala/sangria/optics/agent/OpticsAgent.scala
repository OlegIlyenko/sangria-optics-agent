package sangria.optics.agent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicLongArray, AtomicReference}

import org.slf4j.LoggerFactory
import sangria.execution._
import sangria.schema.{Context, Field, Schema}
import sangria.ast
import sangria.ast.{AstVisitor, AstVisitorCommand}
import sangria.marshalling.InputUnmarshaller
import sangria.optics.util.AstUtil
import sangria.parser.QueryParser
import sangria.renderer.SchemaRenderer
import sangria.validation.TypeInfo

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

case class OpticsAgent[Ctx](schema: Schema[Ctx, _], userContext: Ctx = (), config: OpticsConfig = OpticsConfig.fromEnv)(implicit scheduler: OpticsScheduler, httpClient: OpticsHttpClient, queryNormalizer: OpticsQueryNormalizer, reporter: Reporter, ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(classOf[OpticsAgent[Ctx]])

  private val enabled = new AtomicBoolean(config.enabled)

  // Data we've collected so far this report period.
  private val pendingResults = new AtomicReference[TrieMap[String, OpticsQueryMetric]](TrieMap.empty)

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

      val reportData = pendingResults.getAndSet(TrieMap.empty)
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

  def measure[T](fn: OpticsRequest ⇒ Future[T]): Future[T] =
    measure[T](OpticsRequestInfo.empty)(fn)

  def measure[T](requestInfo: OpticsRequestInfo)(fn: OpticsRequest ⇒ Future[T]): Future[T] = {
    val startWallTime = System.currentTimeMillis()
    val startHrTime = System.nanoTime()

    val queryMetrics = new ConcurrentLinkedQueue[OpticsQueryExecutionMetric]

    val request = new OpticsRequest(queryMetrics, startHrTime)

    fn(request).andThen {
      case _ ⇒
        val endWallTime = System.currentTimeMillis()
        val endHrTime = System.nanoTime()

        reportRequestEnd(startWallTime, startHrTime, endWallTime, endHrTime, queryMetrics, requestInfo)
    }
  }

  private[sangria] def reportRequestEnd(
    startWallTime: Long,
    startHrTime: Long,
    endWallTime: Long,
    endHrTime: Long,
    queryMetrics: ConcurrentLinkedQueue[OpticsQueryExecutionMetric],
    requestInfo: OpticsRequestInfo
  ): Unit = scheduler.scheduleRequestProcessing { () ⇒
    val iterator = queryMetrics.iterator()

    try {
      while (iterator.hasNext) {
        val queryMetric = iterator.next()
        val normalizedQuery = queryMetric.normalizedQuery
        val res = pendingResults.get

        // Initialize per-query state in the report if we're the first of
        // this query shape to come in this report period.
        if (!res.contains(normalizedQuery)) {
          val queryRes = OpticsQueryMetric(TrieMap.empty, TrieMap.empty)
          val typeInfo = new TypeInfo(schema)

          AstVisitor.visitAst(queryMetric.relevantQuery,
            node ⇒ {
              typeInfo.enter(node)

              node match {
                case field: ast.Field ⇒
                  val parent = typeInfo.previousParentType.get
                  val parentMetric = queryRes.perField.getOrElseUpdate(parent.name, TrieMap.empty)

                  parentMetric.put(
                    field.name,
                    OpticsPerFieldMetric(
                      SchemaRenderer.renderTypeName(typeInfo.fieldDef.get.fieldType),
                      latencyBucket))
                case _ ⇒ // ignore
              }

              AstVisitorCommand.Continue
            },
            node ⇒ {
              typeInfo.leave(node)

              AstVisitorCommand.Continue
            })

          res.put(normalizedQuery, queryRes)
        }

        // initialize latency buckets if this is the first time we've had
        // a query from this client type in this period.
        val perClient = res(normalizedQuery).perClient

        if (!perClient.contains(requestInfo.clientName)) {
          perClient.put(requestInfo.clientName, OpticsPerClientMetric(latencyBucket, TrieMap.empty))
        }

        val clientObj = perClient(requestInfo.clientName)

        val clientDuration = endHrTime - startHrTime

        addLatencyToBuckets(clientObj.latencyBuckets, clientDuration)

        if (!clientObj.perVersion.contains(requestInfo.clientVersion)) {
          clientObj.perVersion.put(requestInfo.clientVersion, new AtomicLong(0))
        } else {
          clientObj.perVersion(requestInfo.clientVersion).incrementAndGet()
        }

        val fieldIterator = queryMetric.fieldMetrics.iterator()

        val perField = res(normalizedQuery).perField

        while (fieldIterator.hasNext) {
          val fieldMetric = fieldIterator.next()
          val duration = fieldMetric.endHrTime - fieldMetric.startHrTime

          val parent = perField.getOrElseUpdate(fieldMetric.parentTypeName, TrieMap.empty)
          val field = parent.getOrElseUpdate(fieldMetric.fieldName, OpticsPerFieldMetric(fieldMetric.fieldType, latencyBucket))

          addLatencyToBuckets(field.latencyBuckets, duration)
        }

        if (config.reportTraces && clientObj.latencyBuckets.get(latencyBucketIndex(clientDuration)) == 1L) {
          reportTrace(
            startWallTime,
            endWallTime,
            startHrTime,
            endHrTime,
            clientDuration,
            queryMetric,
            requestInfo)
        }
      }
    } catch {
      case NonFatal(e) ⇒
        logger.error("Something went wrong in request reporting", e)
    }

    Future.successful(())
  }

  private[sangria] def reportTrace(
    startWallTime: Long,
    endWallTime: Long,
    startHrTime: Long,
    endHrTime: Long,
    durationHr: Long,
    metrics: OpticsQueryExecutionMetric,
    requestInfo: OpticsRequestInfo
  ): Unit =
    reporter.sendTrace(startWallTime, endWallTime, startHrTime, endHrTime, durationHr, metrics, requestInfo, httpClient, config)
      .onComplete {
        case Failure(e) ⇒ logger.error("Something went wrong during sending the trace report", e)
        case _ ⇒ // ok!
      }

  private def addLatencyToBuckets(buckets: AtomicLongArray, nanos: Long): Unit =
    buckets.incrementAndGet(latencyBucketIndex(nanos))

  private def latencyBucketIndex(nanos: Long): Int = {
    val micros = nanos / 1000
    val bucket = Math.log(micros) / math.log(1.1)

    if (bucket <= 0) 0
    else if (bucket >= 255) 255
    else math.ceil(bucket).toInt
  }

  private def latencyBucket = new AtomicLongArray(Array.fill(256)(0L))

  // TODO: uncomment
  scheduleSchemaReport()
  scheduleStatsReport()
}

class OpticsRequest(queryMetrics: ConcurrentLinkedQueue[OpticsQueryExecutionMetric], startHrTime: Long)(implicit normalizer: OpticsQueryNormalizer) {
  val queryParseTime: TrieMap[ast.Document, TimeMeasurement] = TrieMap.empty

  def parse(query: String): Try[ast.Document] = {
    val startWallTime = System.currentTimeMillis()
    val startHrTime = System.nanoTime()

    QueryParser.parse(query).map { ast ⇒
      val endWallTime = System.currentTimeMillis()
      val endHrTime = System.nanoTime()

      queryParseTime.put(ast, TimeMeasurement(startWallTime, endWallTime, endHrTime - startHrTime))

      ast
    }
  }

  lazy val middleware = new OpticsMiddleware(queryMetrics, queryParseTime, startHrTime)
}

class OpticsMiddleware(queryMetrics: ConcurrentLinkedQueue[OpticsQueryExecutionMetric], queryParseTime: TrieMap[ast.Document, TimeMeasurement], startHrTime: Long)(implicit normalizer: OpticsQueryNormalizer) extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {
  type QueryVal = ConcurrentLinkedQueue[OpticsFieldMetric]
  type FieldVal = Long

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) =
    new ConcurrentLinkedQueue[OpticsFieldMetric]

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) = {
    val parseTime = queryParseTime.getOrElse(context.queryAst, TimeMeasurement.empty)

    queryMetrics.add(OpticsQueryExecutionMetric(
      context.queryAst,
      context.operationName,
      context.variables,
      context.inputUnmarshaller.asInstanceOf[InputUnmarshaller[Any]],
      0,
      parseTime.durationNanos,
      parseTime.durationNanos,
      parseTime.durationNanos + context.queryReducerTiming.durationNanos + context.validationTiming.durationNanos,
      queryVal))
  }


  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) =
    continue(System.nanoTime())

  def afterField(queryVal: QueryVal, fieldVal: FieldVal, value: Any, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    queryVal.add(OpticsFieldMetric(
      ctx.parentType.name,
      ctx.field.name,
      SchemaRenderer.renderTypeName(ctx.field.fieldType),
      fieldVal,
      System.nanoTime(),
      Seq.empty))

    None
  }

  def fieldError(queryVal: QueryVal, fieldVal: FieldVal, error: Throwable, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    queryVal.add(OpticsFieldMetric(
      ctx.parentType.name,
      ctx.field.name,
      SchemaRenderer.renderTypeName(ctx.field.fieldType),
      fieldVal,
      System.nanoTime(),
      Seq(error.getMessage)))
  }
}

case class OpticsFieldMetric(parentTypeName: String, fieldName: String, fieldType: String, startHrTime: Long, endHrTime: Long, error: Seq[String])
case class OpticsQueryExecutionMetric(
    query: ast.Document,
    operationName: Option[String],
    variables: Any,
    iu: InputUnmarshaller[Any],
    parseHrStart: Long,
    parseHrEnd: Long,
    validateHrStart: Long,
    validateHrEnd: Long,
    fieldMetrics: ConcurrentLinkedQueue[OpticsFieldMetric])(implicit normalizer: OpticsQueryNormalizer) {
  lazy val relevantQuery: ast.Document = AstUtil.removeUnusedOperations(query, operationName)
  lazy val normalizedQuery: String = normalizer.normalizeQuery(relevantQuery)
}

case class OpticsRequestInfo(
  clientName: String = "none",
  clientVersion: String = "nope",
  clientAddress: String = "",
  httpMethod: String = "UNKNOWN",
  host: String = "",
  path: String = "",
  headers: Map[String, String] = Map.empty,
  secure: Boolean = false,
  protocol: String = "")

object OpticsRequestInfo {
  val empty = OpticsRequestInfo()
}

case class OpticsQueryMetric(
  perField: TrieMap[String, TrieMap[String, OpticsPerFieldMetric]],
  perClient: TrieMap[String, OpticsPerClientMetric])

case class OpticsPerFieldMetric(returnType: String, latencyBuckets: AtomicLongArray)
case class OpticsPerClientMetric(latencyBuckets: AtomicLongArray, perVersion: TrieMap[String, AtomicLong])