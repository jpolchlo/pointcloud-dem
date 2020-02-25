import sbt._

object Dependencies {
  val decline        = "com.monovore"                %% "decline"                     % Version.decline
  val gtRaster       = "org.locationtech.geotrellis" %% "geotrellis-raster"           % Version.geotrellis
  val gtPointcloud   = "com.azavea.geotrellis" %% "geotrellis-pointcloud" % Version.gtPC
}
