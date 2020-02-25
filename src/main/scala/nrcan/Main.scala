package nrcan

import cats.data.Validated
import cats.implicits._
import com.monovore.decline._
import geotrellis.pointcloud._
import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.io.geotiff.SinglebandGeoTiff
import geotrellis.raster.render._
import geotrellis.raster.triangulation.DelaunayRasterizer
import geotrellis.vector._
import geotrellis.vector.mesh.CompleteIndexedPointSet
import geotrellis.vector.triangulation.DelaunayTriangulation
import geotrellis.util.RangeReader
import _root_.io.circe._, _root_.io.circe.parser._
import _root_.io.circe.generic.auto._, _root_.io.circe.syntax._
import _root_.io.pdal._

import java.net.URI

object Main extends CommandApp(
  name = "nrcan-ept-to-dem",
  header = "Produce a DEM from a region of an Entwine source",
  main = {
    val targetRegionOpt = Opts.option[String](
      "extent",
      short = "e",
      help = "Extent over which to generate DEM (as string '{<xmin>, <ymin>, <xmax>, <ymax>}') [Default: extent of source]"
    ).mapValidated{ str =>
      val trimmed = str.trim
      if (trimmed.startsWith("{") && trimmed.startsWith("}")) {
        str.replaceAll("[{}]", "").split(",").map(_.toDouble).toSeq match {
          case Seq(xmin, ymin, xmax, ymax) =>
            Validated.valid(Extent(xmin, ymin, xmax, ymax))
          case _ =>
            Validated.invalidNel(s"Extent argument expected in form '{<xmin>, <ymin>, <xmax>, <ymax>}'; Got '$trimmed'")
        }
      } else
        Validated.invalidNel(s"Extent argument expected in form '{<xmin>, <ymin>, <xmax>, <ymax>}'; Got '$trimmed'")
    }.withDefault(Extent(0,0,0,0))

    val targetCellSizeOpt = Opts.option[String](
      "cellsize",
      short = "s",
      help = "Resolution of DEM in units of target CRS (as string '{<width>, <height>}')"
    ).mapValidated{ str =>
      val trimmed = str.trim
      if (trimmed.startsWith("{") && trimmed.startsWith("}")) {
        str.replaceAll("[{}]", "").split(",").map(_.toDouble).toSeq match {
          case Seq(width, height) =>
            Validated.valid(CellSize(width, height))
          case _ =>
            Validated.invalidNel(s"CellSize argument expected in form '{<width>, <height>}'; Got '$trimmed'")
        }
      } else
        Validated.invalidNel(s"CellSize argument expected in form '{<width>, <height>}'; Got '$trimmed'")
    }.withDefault(CellSize(1,1))

    val eptSourceArg = Opts.argument[URI]("Entwine point cloud source")
    val demOutputArg = Opts.argument[URI]("DEM GeoTIFF output")

    (targetRegionOpt,
     targetCellSizeOpt,
     eptSourceArg,
     demOutputArg).mapN {
      (targetRegion,
       targetCellSize,
       eptSource,
       demOutput) => {
         val rr = RangeReader(eptSource.toString + "/ept.json")
         val jsonString = new String(rr.readAll)
         val Right(md) = decode[Extras.EntwineMetadataRaw](jsonString)

         val epsg = md.srs.horizontal.toInt

         println(s"Found ${md.points} points in EPSG $epsg")

         val extent =
           if (math.abs(targetRegion.width) < 1e-8 ||
               math.abs(targetRegion.height) < 1e-8)
             md.extent
         else
           targetRegion

         val targetProjRE = ProjectedRasterExtent(
           RasterExtent(extent, targetCellSize),
           CRS.fromEpsgCode(epsg)
         )

         val samplePipelineJSON =
           s"""
              |{
              |  "pipeline": [
              |    {
              |      "type": "readers.ept",
              |      "filename": "${eptSource.toString}",
              |      "resolution": ${targetCellSize.resolution * 10}
              |    }
              |  ]
              |}
            """.stripMargin
         val samplePipeline = Pipeline(samplePipelineJSON)
         Extras.time("Loaded pointcloud via PDAL")(samplePipeline.execute)

         val pc = Extras.time("Fetched point data"){
           val iter = samplePipeline.getPointViews
           // while(iter.hasNext) {
           //   val pointView = iter.next
           //   println(s"Found a point view with ${pointView.size} points")
           // }
           iter.next.getPointCloud
         }

         val pointSet = new CompleteIndexedPointSet {
           def length = pc.length
           def getX(i: Int) = pc.getX(i)
           def getY(i: Int) = pc.getY(i)
           def getZ(i: Int) = pc.getZ(i)
         }

         val dt = Extras.time(s"Built triangulation from point set of ${pointSet.length} points (sample point=${pointSet.getCoordinate(0)})")(DelaunayTriangulation(pointSet))

         val tile = Extras.time("Rendered DEM"){
           DelaunayRasterizer.rasterizeDelaunayTriangulation(
             dt,
             targetProjRE
           )
         }

         val geotiff = Extras.time("Created GeoTIFF from DEM"){
           SinglebandGeoTiff(
             tile,
             targetProjRE.extent,
             targetProjRE.crs
           )
         }

         Extras.time("Wrote GeoTIFF"){ geotiff.write(demOutput.toString) }
       }
    }
  }
)

object Extras {
  case class Field(
    name: String,
    size: Int,
    `type`: String,
    offset: Option[Double],
    scale: Option[Double]
  )

  case class SRS(
    authority: String,
    horizontal: String,
    wkt: String
  )

  case class EntwineMetadataRaw(
    bounds: Seq[Double],
    boundsConforming: Seq[Double],
    dataType: String,
    hierarchyType: String,
    points: Long,
    schema: Seq[Field],
    span: Int,
    srs: SRS,
    version: String
  ) {
    def extent: Extent = {
      val Seq(xmin, ymin, _, xmax, ymax, _) = bounds
      Extent(xmin, ymin, xmax, ymax)
    }
  }

  def time[T](msg: String)(f: => T): T = {
    val s = System.currentTimeMillis
    val result = f
    val e = System.currentTimeMillis
    val t = "%,d".format(e - s)
    println(s"$msg (in $t ms)")
    result
  }
}
