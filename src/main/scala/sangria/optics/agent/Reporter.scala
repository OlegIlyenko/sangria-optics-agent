package sangria.optics.agent

import java.net.InetAddress

import apollo.optics.proto.reports.{SchemaReport, StatsReport, Timestamp, Field => OpticsField, ReportHeader => OpticsReportHeader, Type => OpticsType}
import com.trueaccord.scalapb.GeneratedMessage
import sangria.renderer.SchemaRenderer
import sangria.schema.{ObjectType, Schema}
import sangria.macros._

import scala.concurrent.Future

class Reporter {
  import Reporter._

  def sendStatsReport(types: Seq[OpticsType], reportData: String, startTimeMs: Long, endTimeMs: Long, durationHr: Long, client: OpticsHttpClient, config: OpticsConfig): Future[Unit] = {
//    val report = StatsReport(
//      header = Some(ReportHeader),
//      startTime = Some(millisToTimestamp(startTimeMs)),
//      endTime = Some(millisToTimestamp(endTimeMs)),
//      realtimeDuration = durationHr,
//      `type` = types
//    )

    Future.failed(new IllegalStateException("not implemented yet :("))
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
