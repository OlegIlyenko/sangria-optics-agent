name := "sangria-optics-agent"
organization := "org.sangria-graphql"
version := "0.1.0-SNAPSHOT"


description := "Sangria apollo-optics agent"
homepage := Some(url("http://sangria-graphql.org"))
licenses := Seq("Apache License, ASL Version 2.0" → url("http://www.apache.org/licenses/LICENSE-2.0"))

scalaVersion := "2.12.1"
crossScalaVersions := Seq("2.11.8", "2.12.1")

scalacOptions ++= Seq("-deprecation", "-feature")

scalacOptions ++= {
  if (scalaVersion.value startsWith "2.12")
    Seq.empty
  else
    Seq("-target:jvm-1.7")
}

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "1.2.0",
  "org.sangria-graphql" %% "sangria-circe" % "1.0.1", // for introspection results
  "org.slf4j" % "slf4j-api" % "1.7.22",
  "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion % "protobuf",

  "com.typesafe.akka" %% "akka-http" % "10.0.1" % Optional,
  "org.slf4j" % "slf4j-simple" % "1.7.22" % Optional,

  // probably don't need this one
  "com.trueaccord.scalapb" %% "scalapb-json4s" % "0.1.6",

  "org.scalatest" %% "scalatest" % "3.0.1" % Test
)

git.remoteRepo := "git@github.com:OlegIlyenko/sangria-optics-agent.git"

// Code generation part

PB.targets in Compile := Seq(
  scalapb.gen() → (sourceManaged in Compile).value
)

resourceGenerators in Compile <+= Def.task {
  val file = (resourceManaged in Compile).value / "META-INF" / "build.properties"
  val contents = "name=%s\nversion=%s".format(name.value, version.value)

  IO.write(file, contents)

  Seq(file)
}

// Publishing

publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := (_ ⇒ false)
publishTo := Some(
  if (version.value.trim.endsWith("SNAPSHOT"))
    "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  else
    "releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2")

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

// Site and docs

site.settings
site.includeScaladoc()
ghpages.settings

// nice *magenta* prompt!

shellPrompt in ThisBuild := { state ⇒
  scala.Console.MAGENTA + Project.extract(state).currentRef.project + "> " + scala.Console.RESET
}

// Additional meta-info

startYear := Some(2016)
organizationHomepage := Some(url("https://github.com/sangria-graphql"))
developers := Developer("OlegIlyenko", "Oleg Ilyenko", "", url("https://github.com/OlegIlyenko")) :: Nil
scmInfo := Some(ScmInfo(
  browseUrl = url("https://github.com/sangria-graphql/sangria-monix.git"),
  connection = "scm:git:git@github.com:sangria-graphql/sangria-monix.git"
))
