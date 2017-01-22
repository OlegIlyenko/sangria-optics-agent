package sangria.optics.agent

import java.io.StringReader
import java.util.Properties

case class BuildConfig(name: String, version: String) {
  def fullName = name + " v" + version
}

object BuildConfig {
  lazy val default = {
    val filePath = "/META-INF/build.properties"
    val stream = this.getClass.getResourceAsStream(filePath)

    if (stream == null) {
      throw new IllegalStateException("Can't read agent build information. Something ia wrong with the classpath. File not found: " + filePath)
    } else {
      val props = new Properties()
      props.load(stream)

      BuildConfig(props.getProperty("name"), props.getProperty("version"))
    }
  }
}
