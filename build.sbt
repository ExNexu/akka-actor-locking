name := "Akka Actor Locking"

organization := "us.bleibinha"

version := "0.0.6"

scalaVersion := "2.12.0"

crossScalaVersions := Seq("2.11.12", "2.12.4")

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

val akkaVersion = "2.5.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test"
)

scalacOptions := Seq(
  "-encoding", "utf8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-target:jvm-1.8",
  "-language:_",
  "-Ywarn-dead-code",
  "-Xlog-reflective-calls"
)


// publishing:

overridePublishSettings

credentials += Credentials(Path.userHome / ".ivy2" / ".us-bleibinha-snapshots-credentials")

credentials += Credentials(Path.userHome / ".ivy2" / ".us-bleibinha-releases-credentials")

publishMavenStyle := true

publishArtifact in Test := false

publishTo := {
  val archiva = "http://bleibinha.us/archiva/repository/"
  if (version.value.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at archiva + "snapshots")
  else
    Some("releases"  at archiva + "releases")
}

pomExtra :=
  <scm>
    <url>https://github.com/ExNexu/akka-actor-locking.git</url>
    <connection>scm:git:git@github.com:ExNexu/akka-actor-locking.git</connection>
  </scm>
  <developers>
    <developer>
      <id>exnexu</id>
      <name>Stefan Bleibinhaus</name>
      <url>http://bleibinha.us</url>
    </developer>
  </developers>
