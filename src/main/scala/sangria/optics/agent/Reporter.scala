package sangria.optics.agent

import java.net.InetAddress

import apollo.optics.proto.reports.{ReportHeader, StatsReport}

import scala.concurrent.Future

class Reporter {
  def sendStatsReport(reportData: String, oldStartTime: Long, currTime: Long, durationHr: Long): Future[Unit] = {
//    val REPORT_HEADER = new ReportHeader({
//      hostname = InetAddress.getLocalHost.getHostName,
//      agentVersion = VERSION,
//      runtime_version: `node ${process.version}`,
//      // XXX not actually uname, but what node has easily.
//      uname: `${os.platform()}, ${os.type()}, ${os.release()}, ${os.arch()})`,
//    });

    val report = new StatsReport()

    Future.failed(new IllegalStateException("not implemented yet :("))
  }
}

object Reporter {
  implicit val default = new Reporter
}
