
package com.foursquare.twofishes.output

import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import com.foursquare.geo.shapes.ShapefileS2Util
import com.foursquare.twofishes.util.{GeometryUtils, StoredFeatureId}
import com.google.common.geometry.S2CellId
import com.mongodb.casbah.Imports._
import com.mongodb.Bytes
import com.novus.salat._
import com.novus.salat.annotations._
import com.novus.salat.dao._
import com.novus.salat.global._
import java.util.concurrent.CountDownLatch
import com.twitter.util.Duration
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.io.{WKBReader, WKBWriter}
import java.io._
import java.net.URI
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{LocalFileSystem, Path}
import org.apache.hadoop.hbase.io.hfile.{TwofishesFoursquareCacheConfig, Compression, HFile}
import org.apache.hadoop.hbase.util.Bytes._
import org.apache.hadoop.io.{BytesWritable, MapFile}
import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TCompactProtocol
import scala.collection.mutable.{HashMap, HashSet, ListBuffer}
import scalaj.collection.Implicits._
import com.weiglewilczek.slf4s.Logging
import akka.actor.ActorSystem
import akka.actor.Props
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

class PolygonIndexer(override val basepath: String, override val fidMap: FidMap) extends Indexer {
  def buildPolygonIndex() {
    val hasPolyCursor = 
      MongoGeocodeDAO.find(MongoDBObject("hasPoly" -> true))
        .sort(orderBy = MongoDBObject("_id" -> 1)) // sort by _id asc
    hasPolyCursor.option = Bytes.QUERYOPTION_NOTIMEOUT

    val writer = buildMapFileWriter(Indexes.GeometryIndex)

    val wkbReader = new WKBReader()

    var index = 0
    // would be great to unify this with featuresIndex
    for {
      g <- hasPolyCursor.grouped(1000)
      group = g.toList
      toFindPolys: Map[Long, ObjectId] = group.filter(f => f.hasPoly).map(r => (r._id, r.polyId)).toMap
      polyMap: Map[ObjectId, PolygonIndex] = PolygonIndexDAO.find(MongoDBObject("_id" -> MongoDBObject("$in" -> toFindPolys.values)))
        .toList
        .groupBy(_._id).map({case (k, v) => (k, v(0))})
      f <- group
      poly <- polyMap.get(f.polyId)
    } {
      if (index % 1000 == 0) {
        logger.info("outputted %d polys so far".format(index))
      }
      index += 1
      writer.append(StoredFeatureId.fromLong(f._id).get, wkbReader.read(poly.polygon))
    }
    writer.close()

    logger.info("done")
  }
}
