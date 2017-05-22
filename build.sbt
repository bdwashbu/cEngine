name := "c_engine"

lazy val root = (project in file("."))
  .settings(
    name         := "c_engine",
    organization := "org.c_engine",
    scalaVersion := "2.11.7",
    version      := "0.1.0-SNAPSHOT",
    test in assembly := {}
  )

scalaSource in Compile := baseDirectory.value / "src"

scalaSource in Test := baseDirectory.value / "tests"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.github.pathikrit" %% "better-files" % "2.17.1",
  "org.scalatest" % "scalatest_2.11" % "3.0.1" % "test"
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

artifact in (Compile, assembly) := {
  val art = (artifact in (Compile, assembly)).value
  art.copy(`classifier` = Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)

//testOptions in Test += Tests.Argument("-P")