package nrcan

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.io.geotiff.OverviewStrategy
import geotrellis.raster.reproject.{Reproject, ReprojectRasterExtent}
import geotrellis.raster.triangulation._
import geotrellis.util.RangeReader
import geotrellis.vector._
import geotrellis.vector.triangulation._
import _root_.io.circe._, _root_.io.circe.parser._
import _root_.io.circe.generic.auto._, _root_.io.circe.syntax._
import _root_.io.pdal._
import org.locationtech.jts.geom.Coordinate
import spire.syntax.cfor._

import java.net.URI
import scala.collection.JavaConversions._

class DEMRasterSource(
  eptSource: String,
  destCRS: Option[CRS],
  resampleTarget: ResampleTarget
) extends RasterSource {

  // private val entwineMetadata = {
  //   val rr = RangeReader(new URI(eptSource).resolve("ept.json").toString)
  //   val jsonString = new String(rr.readAll)
  //   decode[EntwineMetadata.Raw](jsonString) match {
  //     case Right(md) => md
  //     case Left(err) => throw err
  //   }
  // }

  lazy val md: EntwineMetadata = EntwineMetadata(eptSource)

  def attributes = md.attributes
  def attributesForBand(band: Int) = md.attributesForBand(band)
  def bandCount = md.bandCount
  def cellType = md.cellType
  def crs = destCRS.getOrElse(md.crs)
  lazy val gridExtent = {
    lazy val reprojectedRasterExtent =
      ReprojectRasterExtent(
        md.gridExtent,
        Transform(md.crs, crs),
        Reproject.Options.DEFAULT
      )

    resampleTarget match {
      case targetRegion: TargetRegion => targetRegion.region.toGridType[Long]
      case targetAlignment: TargetAlignment => targetAlignment(md.gridExtent)
      case targetDimensions: TargetDimensions => targetDimensions(md.gridExtent)
      case targetCellSize: TargetCellSize => targetCellSize(md.gridExtent)
      case _ => reprojectedRasterExtent
    }
  }
  def name = md.name
  def resolutions = md.resolutions

  def metadata = this

  def reprojection(targetCRS: CRS, resampleTarget: ResampleTarget, method: ResampleMethod, strategy: OverviewStrategy): DEMRasterSource =
    new DEMRasterSource(eptSource, Some(targetCRS), resampleTarget)

  def resample(resampleTarget: ResampleTarget, method: ResampleMethod, strategy: OverviewStrategy): RasterSource =
    new DEMRasterSource(eptSource, destCRS, resampleTarget)

  def read(bounds: GridBounds[Long], bands: Seq[Int]): Option[Raster[MultibandTile]] = {
    val targetRegion = gridExtent.extentFor(bounds, false)
    val srcBounds = ReprojectRasterExtent(
      GridExtent(targetRegion, bounds.width, bounds.height),
      Proj4Transform(crs, md.crs),
      Reproject.Options.DEFAULT
    )
    val bnds = srcBounds.extent

    val pipelineJSON =
      s"""
         |{
         |  "pipeline": [
         |    {
         |      "type": "readers.ept",
         |      "filename": "${eptSource}",
         |      "resolution": ${gridExtent.cellSize.resolution},
         |      "bounds": "([${bnds.xmin}, ${bnds.ymin}], [${bnds.xmax}, ${bnds.ymax}])"
         |    }
         |  ]
         |}
       """.stripMargin
    val pipeline = Pipeline(pipelineJSON)
    pipeline.execute

    val pointViews = pipeline.getPointViews.toList
    val viewSizes = pointViews.map(_.length)
    val (nCoords, starts) = viewSizes.foldLeft(0 -> List.empty[Int]){
      case ((acc, cum), v) => (v + acc, cum :+ acc)
    }
    val coords = Array.ofDim[Coordinate](nCoords)
    viewSizes.zip(starts).zip(pointViews).map{ case ((n, z), pv) => {
      val pc = pv.getPointCloud
      cfor(0)(_ < n, _ + 1){ i =>
        coords(z + i) = new Coordinate(pc.getX(i), pc.getY(i), pc.getZ(i))
      }
    }}

    val dt = DelaunayTriangulation(coords)

    val tile = DelaunayRasterizer.rasterizeDelaunayTriangulation(
      dt,
      srcBounds.toRasterExtent
    )

    Some(Raster(MultibandTile(tile), targetRegion))
  }

  def read(extent: Extent, bands: Seq[Int]): Option[Raster[MultibandTile]] = ???

  def targetCellType: Option[TargetCellType] = ???

  def convert(targetCellType: TargetCellType): RasterSource =
    if (targetCellType.cellType.isFloatingPoint) {
      ???
    } else {
      throw new IllegalArgumentException("DEM height fields may only be of floating point type")
    }
}

object DEMRasterSource {
  def apply(eptSource: String): DEMRasterSource = new DEMRasterSource(eptSource, None, DefaultTarget)
}
