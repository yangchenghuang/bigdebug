/**
 * Created by shrinidhihudli on 2/8/15.
 *
 * --This script covers order by of multiple values.
 * register $PIGMIX_JAR
 * A = load '$HDFS_ROOT/page_views' using org.apache.pig.test.pigmix.udf.PigPerformanceLoader()
 *     as (user, action, timespent:int, query_term, ip_addr, timestamp,
 *         estimated_revenue:double, page_info, page_links);
 * B = order A by query_term, estimated_revenue desc, timespent parallel $PARALLEL;
 * store B into '$PIGMIX_OUTPUT/L10out';
 *
 */
package org.apache.spark.examples.sparkmix

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import java.util.Properties
import java.io.{File, FileInputStream}

import org.apache.spark.lineage.LineageContext._
import org.apache.spark.lineage.LineageContext


object L10 {
  def main(args: Array[String]) {

    val properties = SparkMixUtils.loadPropertiesFile()
    val dataSize = args(0)
    val lineage: Boolean = args(1).toBoolean

    val pigMixPath = properties.getProperty("pigMix") + "pigmix_" + dataSize + "/"
    val outputRoot = properties.getProperty("output") + "pigmix_" + dataSize + "_" + (System.currentTimeMillis() / 100000 % 1000000) + "/"

    new File(outputRoot).mkdir()

    val conf = new SparkConf().setAppName("SparkMix").setMaster("local")
    val sc = new SparkContext(conf)
    val lc = new LineageContext(sc)

    val pageViewsPath = pigMixPath + "page_views/"
    val pageViews = lc.textFile(pageViewsPath)

    lc.setCaptureLineage(lineage)

    val start = System.currentTimeMillis()

    val A = pageViews.map(x => (SparkMixUtils.safeSplit(x, "\u0001", 0), SparkMixUtils.safeSplit(x, "\u0001", 1),
      SparkMixUtils.safeInt(SparkMixUtils.safeSplit(x, "\u0001", 2)), SparkMixUtils.safeSplit(x, "\u0001", 3),
      SparkMixUtils.safeSplit(x, "\u0001", 4), SparkMixUtils.safeSplit(x, "\u0001", 5),
      SparkMixUtils.safeDouble(SparkMixUtils.safeSplit(x, "\u0001", 6)),
      SparkMixUtils.createMap(SparkMixUtils.safeSplit(x, "\u0001", 7)),
      SparkMixUtils.createBag(SparkMixUtils.safeSplit(x, "\u0001", 8))))

    //val B = A.sortBy(_._4, true, properties.getProperty("PARALLEL").toInt).
    //  sortBy(_._7, false, properties.getProperty("PARALLEL").toInt).
    //  sortBy(_._3, true, properties.getProperty("PARALLEL").toInt)

    val B = A.sortBy(r => (r._4, r._7, r._3),true,properties.getProperty("PARALLEL").toInt)

    val end = System.currentTimeMillis()

    B.collect

    lc.setCaptureLineage(false)

    println(end - start)

    sc.stop()

  }
}
