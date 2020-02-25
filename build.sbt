lazy val commonSettings = Seq(
  organization := "nrcan",
  version := "0.1.0-SNAPHOST",
  scalaVersion := Version.scala,
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-language:implicitConversions",
    "-language:reflectiveCalls",
    "-language:higherKinds",
    "-language:postfixOps",
    "-language:existentials",
    "-feature"),
  outputStrategy := Some(StdoutOutput),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },

  fork := true,
  fork in Test := true,
  parallelExecution in Test := false,

  javaOptions ++= Seq(s"-Djava.library.path=${Environment.ldLibraryPath}", "-Xmx15G"),
  test in assembly := {},

  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),

  resolvers ++=
    Seq(
      Resolver.sonatypeRepo("releases"),
      "geosolutions" at "http://maven.geo-solutions.it/",
      "osgeo" at "http://download.osgeo.org/webdav/geotools/",
      "locationtech-releases" at "https://repo.locationtech.org/content/repositories/releases/",
      "LocationTech GeoTrellis Snapshots" at "https://repo.locationtech.org/content/repositories/geotrellis-snapshots",
      "GeoTrellis Bintray Repository" at "http://dl.bintray.com/azavea/geotrellis/"
    ),
  libraryDependencies ++= Seq(
    Dependencies.decline,
    Dependencies.gtRaster,
    Dependencies.gtPointcloud,
    "io.pdal" %% "pdal"        % Version.pdal,
    "io.pdal" %% "pdal-scala"  % Version.pdal,
    "io.pdal" %  "pdal-native" % Version.pdal,
    "org.scalatest"  %% "scalatest" % Version.scalaTest % Test
  ),

  assemblyMergeStrategy in assembly := {
    case m if m.toLowerCase.endsWith("manifest.mf") => MergeStrategy.discard
    case m if m.toLowerCase.matches("meta-inf.*\\.sf$") => MergeStrategy.discard
    case "reference.conf" | "application.conf" => MergeStrategy.concat
    case _ => MergeStrategy.first
  }
)

lazy val root =
  Project("pointcloud", file("."))
    .settings(commonSettings: _*)
