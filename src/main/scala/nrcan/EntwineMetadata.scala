package nrcan

import cats.implicits._
import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.util.RangeReader
import geotrellis.vector.Extent
import _root_.io.circe._, _root_.io.circe.parser._
import _root_.io.circe.generic.auto._, _root_.io.circe.syntax._

import java.net.URI
import scala.util.Try

case class EntwineMetadata(
  name: SourceName,
  crs: CRS,
  cellType: CellType,
  gridExtent: GridExtent[Long],
  resolutions: List[CellSize],
  attributes: Map[String, String]
) extends RasterMetadata {
  val bandCount = 1
  def attributesForBand(i: Int) = attributes
}

object EntwineMetadata {
  def pointsInLevels(base: URI, key: String): Map[Int, Long] = {
    val rr = RangeReader(base.resolve(s"ept-hierarchy/${key}.json").toString)
    val raw = new String(rr.readAll)
    val Right(json) = parse(raw)
    val table = json.asObject.get.toList.toMap.mapValues(_.toString.toLong)

    val recurseKeys = table.filter(_._2 == -1).keys.toSeq
    val joined = (table -- recurseKeys).groupBy(_._1.split("-").head.toInt).mapValues(_.values.sum)
    val nested = recurseKeys.map{ k => pointsInLevels(base, k) }

    nested.fold(joined)(_ combine _)
  }

  def apply(source: String): EntwineMetadata = {
    val src = if (source.endsWith("/")) source else source + "/"
    val raw = Raw(src)
    val counts = pointsInLevels(new URI(src), "0-0-0-0")
    val maxDepth = counts.keys.max

    val resolutions =
      for {
        l <- Range(0,maxDepth+1).toList
      } yield {
        CellSize(
          (raw.extent.width / raw.span) / math.pow(2, l),
          (raw.extent.height / raw.span) / math.pow(2, l)
        )
      }

    EntwineMetadata(
      StringName(src),
      raw.srs.toCRS(),
      DoubleCellType,
      GridExtent[Long](raw.extent, raw.span, raw.span),
      resolutions,
      Map(
        "points" -> raw.points.toString,
        "pointsInLevels" -> counts.toSeq.sorted.map(_._2).mkString(","),
        "minz" -> raw.boundsConforming(2).toString,
        "maxz" -> raw.boundsConforming(5).toString
      )
    )
  }

  case class Field(
    name: String,
    size: Int,
    `type`: String,
    offset: Option[Double],
    scale: Option[Double]
  )

  case class SRS(
    authority: Option[String],
    horizontal: Option[String],
    vertical: Option[String],
    wkt: Option[String]
  ) {
    def toCRS(defaultCRS: CRS = LatLng): CRS = {
      val parsed: Option[CRS] = for {
        txt <- wkt
        crs <- CRS.fromWKT(txt)
      } yield crs
      lazy val fromCode =
        if (authority.isDefined && authority.get.toLowerCase == "epsg") {
          val tmp: CRS = horizontal
            .map{epsg => CRS.fromEpsgCode(epsg.toInt)}
            .getOrElse(defaultCRS)
          tmp
        } else
          defaultCRS
      parsed.getOrElse(fromCode)
    }
  }

  case class Raw(
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

  object Raw {
    def apply(eptSource: String) = {
      val rr = RangeReader(new URI(eptSource).resolve("ept.json").toString)
      val jsonString = new String(rr.readAll)
      decode[Raw](jsonString) match {
        case Right(md) => md
        case Left(err) => throw err
      }
    }
  }
}
