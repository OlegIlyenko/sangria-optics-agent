package sangria.optics.agent

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLongArray

import apollo.optics.proto.reports.{Error, FieldStat, SchemaReport, StatsPerClientName, StatsPerSignature, StatsReport, Timestamp, Trace, TracesReport, TypeStat, Field => OpticsField, ReportHeader => OpticsReportHeader, Type => OpticsType}
import com.google.protobuf.ByteString
import com.trueaccord.scalapb.GeneratedMessage
import com.trueaccord.scalapb.json.JsonFormat
import io.circe.Json
import sangria.renderer.{QueryRenderer, SchemaRenderer}
import sangria.ast
import sangria.schema.{ObjectType, Schema}
import sangria.macros._
import sangria.marshalling.{InputUnmarshaller, MarshallingUtil}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.VectorBuilder
import scala.concurrent.Future

class Reporter {
  import Reporter._

  def sendStatsReport(
    types: Seq[OpticsType],
    reportData: TrieMap[String, OpticsQueryMetric],
    startTimeMs: Long,
    endTimeMs: Long,
    durationHr: Long,
    client: OpticsHttpClient,
    config: OpticsConfig
  ): Future[Unit] = {
    val perSignature =
      reportData.toMap.map { case (query, sigMetric) ⇒
        val perClient =
          sigMetric.perClient.toMap.map { case (client, clientMetric) ⇒
            val versions = clientMetric.perVersion
            val latencyCount = trimLatencyBuckets(clientMetric.latencyBuckets)

            client → StatsPerClientName(latencyCount, countPerVersion = versions.toMap.mapValues(_.get()))
          }

        val perType = new VectorBuilder[TypeStat]

        sigMetric.perField.foreach { case (parentType, typeMetric) ⇒
          val perField = new VectorBuilder[FieldStat]

          typeMetric.foreach { case (fieldName, fieldMetric) ⇒
            perField += FieldStat(fieldName, fieldMetric.returnType, trimLatencyBuckets(fieldMetric.latencyBuckets))
          }


          perType += TypeStat(parentType, perField.result())
        }

        query → StatsPerSignature(perClient, perType.result())
      }

    val report = StatsReport(
      header = Some(ReportHeader),
      startTime = Some(millisToTimestamp(startTimeMs)),
      endTime = Some(millisToTimestamp(endTimeMs)),
      realtimeDuration = durationHr,
      `type` = types,
      perSignature = perSignature)

    sendRequest("api/ss/stats", client, config, report)
  }

  private def trimLatencyBuckets(buckets: AtomicLongArray): Vector[Long] = {
    val builder = new VectorBuilder[Long]

    for (i ← 0 until buckets.length) {
      builder += buckets.get(i)
    }

    val vector = builder.result()

    var max = buckets.length

    while (max > 0 && vector(max - 1) == 0L) {
      max -= 1
    }

    vector.slice(0, max)
  }

  def sendTrace(
    startWallTime: Long,
    endWallTime: Long,
    startHrTime: Long,
    endHrTime: Long,
    durationHr: Long,
    metrics: OpticsQueryExecutionMetric,
    requestInfo: OpticsRequestInfo,
    client: OpticsHttpClient,
    config: OpticsConfig
  ): Future[Unit] = {
    val vars: Map[String, ByteString] =
      if (config.reportVariables) {
        implicit val iu = metrics.iu
        import sangria.marshalling.circe.CirceMarshallerForType

        val json = MarshallingUtil.convert[Any, Json](metrics.variables)

        json.asObject match {
          case Some(obj) ⇒ obj.toMap.mapValues(v ⇒ ByteString.copyFromUtf8(v.noSpaces))
          case None ⇒ Map.empty
        }
      } else Map.empty

    val details =
      Trace.Details(
        operationName = metrics.operationName getOrElse "",
        rawQuery = QueryRenderer.render(metrics.relevantQuery, QueryRenderer.Compact),
        variables = vars)

    val httpInfo = Trace.HTTPInfo(
      method = Trace.HTTPInfo.Method.fromName(requestInfo.httpMethod).getOrElse(Trace.HTTPInfo.Method.UNKNOWN),
      host = requestInfo.host,
      path = requestInfo.path,
      headers = requestInfo.headers,
      secure = requestInfo.secure,
      protocol = requestInfo.protocol)

    val fieldBuilder = new VectorBuilder[Trace.Node]

    val fieldIter = metrics.fieldMetrics.iterator()

    while (fieldIter.hasNext) {
      val child = fieldIter.next()

      fieldBuilder += Trace.Node(
        alias = s"${child.parentTypeName}.${child.fieldName}",
        `type` = child.fieldType,
        startTime = child.startHrTime - startHrTime,
        endTime = child.endHrTime - startHrTime,
        error = child.error map (e ⇒ Error(e)))
    }

    val executeNode = Trace.Node(
      startTime = metrics.validateHrEnd,
      endTime = endHrTime,
      child = fieldBuilder.result())

    val parseNode = Trace.Node(
      startTime = metrics.parseHrStart,
      endTime = metrics.parseHrEnd)

    val validateNode = Trace.Node(
      startTime = metrics.validateHrStart,
      endTime = metrics.validateHrEnd)

    val trace = Trace(
      startTime = Some(millisToTimestamp(startWallTime)),
      endTime = Some(millisToTimestamp(endWallTime)),
      durationNs = durationHr,
      signature = metrics.normalizedQuery,
      details = Some(details),
      clientName = requestInfo.clientName,
      clientVersion = requestInfo.clientVersion,
      clientAddress = requestInfo.clientAddress,
      http = Some(httpInfo),
      execute = Some(executeNode),
      parse = Some(parseNode),
      validate = Some(validateNode))

    val report = TracesReport(Some(ReportHeader), Seq(trace))

    sendRequest("api/ss/traces", client, config, report)
  }

  def sendSchemaReport(types: Seq[OpticsType], schemaIntrospection: String, client: OpticsHttpClient, config: OpticsConfig): Future[Unit] = {
    val report =
      SchemaReport(
        header = Some(ReportHeader),
        introspectionResult = schemaIntrospection,
        `type` = types)

    sendRequest("api/ss/schema", client, config, report)
  }

  private def sendRequest(path: String, client: OpticsHttpClient, config: OpticsConfig, message: GeneratedMessage) = {
    val delim = if (config.endpointUrl.endsWith("/")) "" else "/"

    client.postRequest(
      url = config.endpointUrl + delim + path,
      headers = Map(
        "user-agent" → ReportHeader.agentVersion,
        "x-api-key" → config.apiKey),
      payload = message.toByteString.toByteArray)
  }
}

object Reporter {
  implicit val default = new Reporter

  lazy val ReportHeader = OpticsReportHeader(
    hostname = InetAddress.getLocalHost.getHostName,
    agentVersion = BuildConfig.default.fullName,
    runtimeVersion = System.getProperty("java.vm.name"),
    // not actually uname, but what JVM has easily.
    uname = System.getProperty("os.name") + " " + System.getProperty("os.arch") + " " + System.getProperty("os.version"))

  def millisToTimestamp(millis: Long): Timestamp =
    Timestamp(millis / 1000, ((millis % 1000) * 1e6).toInt)

  def typesFromSchema(schema: Schema[_, _]): Seq[OpticsType] =
    schema.typeList
      .collect {case tpe: ObjectType[_, _] if !Schema.isIntrospectionType(tpe.name) ⇒  tpe}
      .map(tpe ⇒ OpticsType(tpe.name, tpe.fields map (f ⇒ OpticsField(f.name, SchemaRenderer.renderTypeName(f.fieldType)))))

  val opticsIntrospectionQuery =
    graphql"""
      query ShorterIntrospectionQuery {
        __schema {
          queryType { name }
          mutationType { name }
          subscriptionType { name }
          types {
            ...FullType
          }
          directives {
            name
            # description
            locations
            args {
              ...InputValue
            }
          }
        }
      }

      fragment FullType on __Type {
        kind
        name
        # description
        fields(includeDeprecated: true) {
          name
          # description
          args {
            ...InputValue
          }
          type {
            ...TypeRef
          }
          isDeprecated
          # deprecationReason
        }
        inputFields {
          ...InputValue
        }
        interfaces {
          ...TypeRef
        }
        enumValues(includeDeprecated: true) {
          name
          # description
          isDeprecated
          # deprecationReason
        }
        possibleTypes {
          ...TypeRef
        }
      }

      fragment InputValue on __InputValue {
        name
        # description
        type { ...TypeRef }
        # defaultValue
      }

      fragment TypeRef on __Type {
        kind
        name
        ofType {
          kind
          name
          ofType {
            kind
            name
            ofType {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                  ofType {
                    kind
                    name
                    ofType {
                      kind
                      name
                    }
                  }
                }
              }
            }
          }
        }
      }
    """
}
