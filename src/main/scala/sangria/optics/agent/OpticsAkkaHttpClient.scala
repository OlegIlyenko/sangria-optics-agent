package sangria.optics.agent

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.{HttpHeader, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.slf4j.LoggerFactory

import scala.concurrent.Future

class OpticsAkkaHttpClient(implicit system: ActorSystem, materializer: Materializer) extends OpticsHttpClient {
  import system.dispatcher

  val logger = LoggerFactory.getLogger(classOf[OpticsAkkaHttpClient])

  override def postRequest(url: String, headers: Map[String, String], payload: Array[Byte]): Future[Unit] = {
    val actualHeaders = headers.map{case (k, v) ⇒ HttpHeader.parse(k, v) match {
      case HttpHeader.ParsingResult.Ok(header, _) ⇒ header
      case HttpHeader.ParsingResult.Error(e) ⇒ throw new IllegalStateException(s"Can't create an HTTP header ($k: $v): ${e.summary}")
    }}

    Http().singleRequest(Post(Uri(url), payload).withHeaders(actualHeaders.toSeq: _*)).flatMap { resp ⇒
      if (resp.status.isFailure())
        Unmarshal(resp).to[String].map { body ⇒
          throw new IllegalStateException(s"Unsuccessful status code: ${resp.status}. url = $url, body = $body")
        }
      else if (logger.isDebugEnabled)
        Unmarshal(resp).to[String].map { body ⇒
          logger.info(s"Got response from optics server. url = $url, status = ${resp.status}, body = $body")

          ()
        }
      else
        Future.successful(())
    }
  }
}

object OpticsAkkaHttpClient {
  implicit def client(implicit system: ActorSystem, materializer: Materializer) = new OpticsAkkaHttpClient
}
