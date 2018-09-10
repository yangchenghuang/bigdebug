package org.apache.spark.lineage.demo

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.ignite.Ignition
import org.apache.spark._
import org.apache.spark.lineage.LineageContext
import org.apache.spark.lineage.perfdebug.lineageV2.{LineageCacheRepository, LineageWrapper}
import org.apache.spark.lineage.perfdebug.perftrace.PerfLineageWrapper
import org.apache.spark.lineage.perfdebug.utils.PerfLineageUtils
import org.apache.spark.lineage.rdd.Lineage
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag


abstract class LineageBaseApp(val lineageEnabled: Boolean = true,
                              val threadNum: Option[Int] = None,
                              val sparkLogsEnabled: Boolean = false,
                              val withIgnite: Boolean = true,
                              val rewriteAllHadoopFiles: Boolean = true,
                              val defaultPrintLimit: Option[Int] = Some(25)) {
  
  val appName: String = getClass.getSimpleName.dropRight(1) // drop the $ at the end for the
  private val useIgnite = lineageEnabled && withIgnite
  // corresponding object
  private var lc: LineageContext = _
  lazy val appId: String = lc.sparkContext.applicationId
  
  final def main(args: Array[String]): Unit = {
    Lineage.measureTimeWithCallback({
      lc = initContext(args)
      try {
        Lineage.measureTimeWithCallback({
          run(lc, args)
        }, x=> println(s"run() time: $x ms"))
      }
      finally {
        lc.sparkContext.stop()
        if (useIgnite) {
          // Spark/Titian will try to finalize the caches and upload to ignite after the job itself
          // has run, so sleep a few seconds to allow that lineage to be uploaded.
          Thread.sleep(10000)
          LineageCacheRepository.close()
        }
    
      }
    }, x => println(s"Total time: $x ms"))
  }
  
  def run(lc: LineageContext, args: Array[String]): Unit
  
  private def initContext(args: Array[String]): LineageContext = {
    val conf = initConf(args, buildDefaultConfiguration())
    val sc = new SparkContext(conf)
    val lc = new LineageContext(sc)
    lc.setCaptureLineage(lineageEnabled)
    
    if(useIgnite) {
      Ignition.setClientMode(true)
      LineageCacheRepository.useSimpleIgniteCacheRepository(sc)
    }
    
    if(!sparkLogsEnabled) {
      sc.setLogLevel("ERROR") // for cleaner logs
    }
    lc
  }
  
  /**
   * Endpoint to override the typical spark configuration.
   */
  def initConf(args: Array[String], defaultConf: SparkConf): SparkConf = {
    defaultConf // by default, this does nothing. Users can override if they have arg-specific confs
  }
  
  private def buildDefaultConfiguration(): SparkConf = {
    new SparkConf().setAppName(appName).setMaster(s"local[${threadNum.getOrElse("*")}]")
  }
  
  /** You can optionally use the LineageBaseApp rewriteAllHadoopFiles to avoid setting overwrite
   * for each */
  def tempHadoopRDD[T](rddName: String,
                       dataBlock: => Seq[T], // ignored if file exists and overWrite is false
                       minPartitions: Option[Int] = None,
                       overwriteIfExists: Boolean = false,
                       requireExactPartitionCount: Boolean = false
                      )(implicit classTag: ClassTag[T]): Lineage[String] = {
    // Generate data file of n random ints in [0, limit)
    require(!rddName.contains(Path.SEPARATOR), "File name should not be a full path")
    val tmpFileName = tempHadoopFileName(rddName)
    val fs = FileSystem.get(lc.sparkContext.hadoopConfiguration)
    val filePath = new Path(tmpFileName)
    if(rewriteAllHadoopFiles || overwriteIfExists || !fs.exists(filePath)) {
      if(fs.exists(filePath)) fs.delete(filePath, true)
      val data = dataBlock // call by-name to only evaluate once
      val msgString = "hadoop file consisting of inputs (up to 5 shown) " + data.take(5)
      val sc = lc.sparkContext // use sc because we definitely don't need lineage or anything
      // related to write to file
      println("Generating " + msgString)
      val dataRDD: RDD[T] = minPartitions match {
        case Some(x) => sc.parallelize(data, x)
        case None => sc.parallelize(data)
      }
      dataRDD.saveAsTextFile(tmpFileName)
      println("Finished generating " + msgString)
    } else {
      println("Not generating new data file - file already exists and overwrite flag is disabled")
    }
    minPartitions match {
      case Some(min) => {
        val result = lc.textFile(tmpFileName, min)
        val resultPartitionCount = result.getNumPartitions
        if(resultPartitionCount != min) {
          val msg = "Generated hadoop file has more partitions than minimum " +
            s"specified: $resultPartitionCount > $min"
          if(requireExactPartitionCount) {
            throw new RuntimeException("ERROR: " + msg)
          } else {
            println("WARNING: " + msg)
          }
          
        }
        result
      }
      case None => lc.textFile(tmpFileName)
    }
  }
  
  def tempHadoopFileName(rddName: String): String = s"/tmp/${appName}_${rddName}"
  
  // alias to the other function
  def printRDDWithMessage(rdd: RDD[_],
                          msg: String,
                          printSeparatorLines: Boolean = true,
                          limit:Option[Int] = defaultPrintLimit,
                          cacheRDD: Boolean = false
                         ): Unit = withPrintLimitWarning(limit) {
    // TODO WARNING - ENABLING CACHE FLAG REALLY SCREWS THINGS UP FOR UNKNOWN REASONS
    PerfLineageUtils.printRDDWithMessage(rdd, msg, printSeparatorLines, limit, cacheRDD)
  }
  
  /** Traces back this lineage wrapper to all input sources and outputs the original inputs. */
  def printHadoopSources(lineageWrapper: LineageWrapper,
                         rawRdds: RDD[String]*
                        ): Unit = withPrintLimitWarning(defaultPrintLimit){
    val hadoopSourceLineageWrappers = lineageWrapper.traceBackAllSources()
    assert(hadoopSourceLineageWrappers.length == rawRdds.length, "Must have equal number of " +
      "sources and raw input RDDs")
    //hadoopSourceLineageWrappers.foreach(println)
    hadoopSourceLineageWrappers.zipWithIndex.foreach(
      { case (lin, index) =>
        printRDDWithMessage(lin.lineageCache.sortBy(_._1),
                            s"Hadoop source Lineage for #$index")
      })
    
    
    val rawInputs = hadoopSourceLineageWrappers.zip(rawRdds).map(
      { case (lin, hadoop) => lin.joinInputTextRDD(hadoop) })
    rawInputs.zipWithIndex.foreach(
      { case (input, index) =>
        //printRDDWithMessage(rawRdds(index), s"Raw hadoop dataset for RDD #$index")
        printRDDWithMessage(input, s"Raw inputs from RDD #$index")
      })
  }
  
  def printLineageWrapperWithMessage(lineageWrapper: LineageWrapper, msg: String,
                                     limit: Option[Int] = defaultPrintLimit
                                    ): Unit = withPrintLimitWarning(limit){
    val rdd = lineageWrapper match {
      case p: PerfLineageWrapper => p.perfCache.rdd
      case _ => lineageWrapper.lineageCache.rdd
    }
    printRDDWithMessage(rdd, msg, limit=limit)
  }
  
  def debugLineageWrapperTraceback(lineageWrapper: LineageWrapper,
                                   limit: Option[Int] = defaultPrintLimit
                                  ): Unit = withPrintLimitWarning(limit){
    var curr = lineageWrapper
    val resultLimit = limit.getOrElse(Integer.MAX_VALUE)
    var i = 0
    while (curr.hasParent()) {
      val currCacheResults = curr.lineageCache.collect().take(resultLimit)
      val currTap = curr.tap
      val nextCacheResults = curr.dependencies.head.fullLineageCache.collect().take(resultLimit)
      curr = curr.traceBackwards()
      val nextTap = curr.tap
      var joinedResults = curr.lineageCache.collect().take(resultLimit)
      println(s"------------- STAGE $i start --------------")
      println(s"PREVIOUS $currTap")
      currCacheResults.foreach(println)
      println(s"NEXT $nextTap")
      nextCacheResults.foreach(println)
      println("RESULTS")
      joinedResults.foreach(println)
      println(s"------------- STAGE $i END --------------")
      i += 1
    }
  }
  
  private def printLimitWarning(limit: Option[Int]): Unit =
    limit.foreach(definedLimit => println("Default print limit is set to " + definedLimit))
  
  var _hasWarnedPrintLimit = false
  private def withPrintLimitWarning[R](limit: Option[Int])(block: => R): R = {
    if(!_hasWarnedPrintLimit) {
      printLimitWarning(limit)
      _hasWarnedPrintLimit = true
    }
    block
  }
}
