val circeVersion = "0.10.0"

libraryDependencies ++= Seq(
  "org.scodec" %% "scodec-bits" % "1.1.6",

  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,

  "org.scalatest" %% "scalatest" % "3.0.8" % "test",
  "org.scalacheck" %% "scalacheck" % "1.14.1" % "test",
)

publishMavenStyle in ThisBuild := true

publishArtifact in Test := false

publishTo in ThisBuild :=
  Some(if (isSnapshot.value) Opts.resolver.sonatypeSnapshots else Opts.resolver.sonatypeStaging)

pomExtra in ThisBuild :=
  <developers>
    <developer>
      <id>Oskin1</id>
      <name>Ilya Oskin</name>
    </developer>
  </developers>

// set bytecode version to 8 to fix NoSuchMethodError for various ByteBuffer methods
// see https://github.com/eclipse/jetty.project/issues/3244
// these options applied only in "compile" task since scalac crashes on scaladoc compilation with "-release 8"
// see https://github.com/scala/community-builds/issues/796#issuecomment-423395500
scalacOptions in(Compile, compile) ++= (if (scalaBinaryVersion.value == "2.11") Seq() else Seq("-release", "8"))


val credentialFile = Path.userHome / ".sbt" / ".ergo-sonatype-credentials"
credentials ++= (for {
  file <- if (credentialFile.exists) Some(credentialFile) else None
} yield Credentials(file)).toSeq
