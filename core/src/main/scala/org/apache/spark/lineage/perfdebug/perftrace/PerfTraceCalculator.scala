package org.apache.spark.lineage.perfdebug.perftrace

import org.apache.spark.Partitioner
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.lineage.perfdebug.lineageV2.LineageWrapper
import org.apache.spark.lineage.perfdebug.utils.CacheDataTypes.{CacheValue, EndOfStageCacheValue, PartitionWithRecId, TapHadoopLRDDValue}
import org.apache.spark.lineage.perfdebug.utils.{PartitionWithRecIdPartitioner, PerfLineageUtils}
import org.apache.spark.lineage.rdd.{TapHadoopLRDD, _}
import org.apache.spark.rdd.RDD
import org.apache.spark.rdd.RDD._
import org.apache.spark.lineage.perfdebug.utils.TapUtils._

import scala.reflect.ClassTag


// Out of laziness, most of these are not scoped properly.
// Some of these variables are marked as transient - that's because Spark's closure cleaning
// attempts to serialize all fields in the object, but many (most) aren't needed within the RDD
// computations.
case class PerfTraceCalculator(@transient initWrapper: LineageWrapper,
                               accFn: (Long, Long) => Long = _ + _,
                               aggFn: (Long, Long) => Long = Math.max,
                               printDebugging: Boolean = false,
                               printLimit: Option[Int] = None) {
  import PerfTraceCalculator._
  /** Entry point for public use */
  def calculate(): PerfLineageWrapper = {
    val perfLineageCache = perfTraceRecursiveHelper(initWrapper)
    initWrapper.asPerfLineageWrapper(perfLineageCache)
  }
  
  type PartitionId = Int
  type InputId = PartitionWithRecId
  type OutputId = PartitionWithRecId
  type Latency = Long
  type InputLatency = Latency
  type UnAccumulatedLatency = Latency // for OutputValue latencies that haven't been acc'ed yet.
  type OutputLatency = Latency
  
  // TODO jteoh: cache the perfTrace output RDDs somewhere and save the RDD so it's not
  // recomputed later.
  
  /** The 'main' body of code appears after many of these methods (which reuse arguments) are
   * defined. The main computation is actually [[perfTraceRecursiveHelper]]
   */
  
  // ---------- START DEBUG PRINT HELPERS ----------
  def debugPrint(rdd: => RDD[_], msg: => String): Unit = {
    if(printDebugging)
      PerfLineageUtils.printRDDWithMessage(rdd,
                                           "[DEBUG PERFORMANCE TRACING] " + msg,
                                           limit = printLimit)
  }

  def debugPrintStr(msg: => String): Unit = {
    if(printDebugging) println(msg)
  }
  
  // ---------- END DEBUG PRINT HELPERS ----------
  
  // ---------- START AGG STATS REPO HELPERS ----------
  @transient
  private val aggStatsRepo = AggregateStatsStorage.getInstance()
  
  // Only for use with shuffle-based RDDs
  def getRDDAggStats(latencyStatsTap: LatencyStatsTap[_]): Map[PartitionId, AggregateLatencyStats] =
    aggStatsRepo.getAllAggStatsForRDD(initWrapper.lineageAppId, latencyStatsTap)
  
  
  def getRDDAggStats(
                     latencyStatsTap: LatencyStatsTap[_],
                     numPartitions: Int
                    ): Map[PartitionId, AggregateLatencyStats] =
    aggStatsRepo.getAllAggStats(initWrapper.lineageAppId, latencyStatsTap.id, numPartitions)
  // ---------- END AGG STATS REPO HELPERS ----------
  
  // ---------- START PERF TRACE HELPERS ----------
  def prevPerfRDDs(curr: LineageWrapper): Seq[RDD[(InputId,InputLatency)]] = {
    // most of the time (except post cogroup), this is a single wrapper.
    val prevWrappers = curr.dependencies.indices.map(curr.traceBackwards)
    //    prevWrappers.foreach(prevWrapper =>
    //                           debugPrint(prevWrapper.lineageCache, "Lineage cache for " +
    //                             prevWrapper.tap))
    // project out input values because not required here/assumed to have already been used.
    val prevPerfWrappers = prevWrappers.map(perfTraceRecursiveHelper)
    val prevRdds = prevPerfWrappers.map(_.map(r => (r._1, r._2._2)))
    //    prevRdds.zip(prevWrappers).foreach {
    //      case (prev, prevWrapper) => debugPrint(prev.sortBy(_._2, ascending = false),
    //                                             "Latency inputs from " + prevWrapper.tap)
    //    }
    prevRdds
  }
  
  /** For getting the first prevPerfRDD. Includes an assert to ensure no incorrect usage! */
  def prevPerfRDD(curr: LineageWrapper): RDD[(InputId, InputLatency)] = {
    assert(curr.dependencies.length == 1, "Single PerfRdd can only be returned if there is only" +
      " one dependency. Otherwise, use the more general prevPerfRdds method.")
    prevPerfRDDs(curr).head
  }
  
  // ---------- END PERF TRACE HELPERS ----------
  
  
  /** Used within start-of-stage tracing, eg PostCoGroup, where there can be multiple
   *  input RDDs but no partial latency. This will also automatically incorporate the shuffle agg
   *  stats. */
  def joinInputRddsWithAgg(aggTap: LatencyStatsTap[_],
                           aggTapPartitionCount: Int,
                           base: RDD[(OutputId, CacheValue)],
                           first: RDD[(InputId, InputLatency)],
                           secondOption: Option[RDD[(InputId, InputLatency)]] = None
                          ): RDD[(OutputId, (CacheValue, OutputLatency))] = {
    // assumption: each input latency RDD is uniquely keyed. for example, they come from the
    // prevPerfRDD set of methods. Additionally, there are no assumptions made on the
    // partitioning scheme for any input arguments. Finally, each record in base can have
    // multiple inputs - in the case when there are multiple input RDDs, the only guarantee is
    // that these record inputs come from at least one of the two input RDDs (not necessarily
    // both)
    // In practice, Titian does not support cogroup beyond 2 RDDs. This method is implemented
    // under such an assumption, but should be easily extendable if required.
    
    // We define a partitioner upfront to help boost performance (hopefully). This is used for
    // partitioning when the entire record (PartitionRecWithId + CacheValue) is the key.
    val partitioner = secondOption match {
      case Some(_) =>
        // We're going to do a 3-way join eventually after some reduceByKeys, so use a
        // consistent scheme to improve performance.
        val numPartitions = Partitioner.defaultPartitioner(base, first, secondOption.get).numPartitions
        
        // insufficient by itself, because partition key is a (OutputId, CacheValue) rather than
        // just OutputId
        val inner = new PartitionWithRecIdPartitioner(base.getNumPartitions)
        
        val wrapperPartitioner = new Partitioner() {
          override def numPartitions: PartitionId = inner.numPartitions
  
          override def getPartition(key: Any): PartitionId =
            inner.getPartition(key.asInstanceOf[(PartitionWithRecId, _)]._1)
        }
        wrapperPartitioner
      case None =>
        // In the future, we might still want to partition based on number in base, as base
        // tends to be the start of a stage (so the end-of-stage tap could have a 1-1 partition
        // mapping if we appropriately store the cache data in the same partitioning scheme)
        Partitioner.defaultPartitioner(base, first)
    }
    
    // The key here is inherently not unique, eg an output may come from multiple input
    // partitions. After joining to input latencies, we have to reduce by the output key.
    val inpToOutputMapping: RDD[(InputId, (OutputId, CacheValue))] =
    base.flatMap(r => r._2.inputIds.map((_, r)))
    
    // Note: Although join can use a partitioner, the output of the join is keyed by InputId
    // which has no exploitable correlation. Thus, we don't get any benefit from specifying a
    // partitioner here (though we will partition later when reducing by key=OutputId).
    val firstJoin: RDD[((OutputId, CacheValue), InputLatency)] =
      LineageWrapper.joinLineageKeyedRDDs(inpToOutputMapping, first, useShuffle = true).values
    val firstAggLatenciesWithCounts: RDD[((OutputId, CacheValue), AggResultWithCount)] =
      firstJoin.reduceByKeyWithCount(partitioner, aggFn)
    
    val resultWithoutShuffleStats: RDD[((OutputId, CacheValue), AggResultWithCount)] = secondOption
    match {
      case Some(second) =>
        val secondJoin: RDD[((OutputId, CacheValue), InputLatency)] =
          LineageWrapper.joinLineageKeyedRDDs(inpToOutputMapping, second, useShuffle = true).values
        val secondAggLatenciesWithCounts: RDD[((OutputId, CacheValue), AggResultWithCount)] =
          secondJoin.reduceByKeyWithCount(partitioner, aggFn)
      
        // Since inputs for each record in base are not guaranteed to appear in both RDDs, we
        // have to do the equivalent of a full outer join. At this point we also know both first
        // and second are uniquely keyed, so I optimize the fullOuterJoin code slightly.
        // In practice, we could also have included `base`, but it's not necessary due to the
        // assumption that every input record from `base` will show up in at least one of
        // first/second (ie a union of the OutputIds that appear in the first/second aggregated
        // latencies should equal the original set of OutputIds in `base`)
        // As both RDDs already use the same partitioner, this map-side 'reduce' should be
        // efficient.
        val totalAggregatedLatenciesWithCounts: RDD[((OutputId, CacheValue), AggResultWithCount)] =
          firstAggLatenciesWithCounts.cogroup(secondAggLatenciesWithCounts,partitioner).map {
            case ((outputId, outputValue), aggCountIterTuple) => {
              val aggCount = aggCountIterTuple match {
                case (vs, Seq()) => vs.head
                case (Seq(), ws) => ws.head
                case (vs, ws) => vs.head.mergeWith(ws.head, aggFn)
              }
              ((outputId, outputValue), aggCount) // reordering/structuring schema.
            }
          }
      
        totalAggregatedLatenciesWithCounts
      case None =>
        // No second RDD to join, so we're done after the reduceByKey that's already been
        // applied. All we need to do is remap the output schema. Note that for these base
        // RDDs, we expect no partial latency for accumulation purposes.
        firstAggLatenciesWithCounts
    }
    // Use aggTapPartitionCount to avoid calling aggTap.getNumPartitions, which might rely on a
    // getPartitions implementation that is broken post-session and serialization
    val result = addLatencyStats(resultWithoutShuffleStats, aggTap, aggTapPartitionCount)
    result
  }
  
  // Return type: Ideally we can actually drop the CacheValue field in all but the initial call
  // . I can't think of a simple way to do so in the code (since it affects RDD schemas), so
  // for now all recursive calls will return the output value, which will get dropped
  // (specifically in prevPerfRdd).
  // If performance is a major factor, one option would be to 'mirror' the code so that these
  // values aren't generated in the first place.
  def perfTraceRecursiveHelper(curr: LineageWrapper): PerfLineageCache = {
    // re: generics usage, ideally we'd store an RDD[(ID, (_ <: CacheValue, Latency))] and convert
    // at  the end, but I'm not skilled enough with Scala generics (existential types??) to do
    // this. Instead, we just wrap/cast to PerfLineageCache at the end.
    val currTap = curr.tap
    
    // First compute the latencies based on the predecessors, applying agg/acc as needed.
    val result = currTap match {
      case _ if currTap.isSourceTap =>
        currTap match {
          case _: TapHadoopLRDD[_,_] =>
            type OutputValue = TapHadoopLRDDValue
            // TapHadoop has no predecessor, so it only needs to extract latency from the value. Yay!
            val result = curr.lineageCache.withValueType[OutputValue].mapValues(hadoopValue => {
              // BTS: default is zero right now because we don't measure read time
              (hadoopValue, hadoopValue.latency)
            })
            PerfLineageCache.toPerfLineageCache(result)
          case _ =>
            throw new UnsupportedOperationException(s"Unsupported source tap RDD: $currTap")
        }
      case _ if currTap.isStartOfStageTap && currTap.hasPredecessor =>
        // eg PostShuffle, PostCoGroup
        // This is the hardest case - there can be multiple dependencies, so we code
        // appropriately. Since we're dealing with RDD objects, it's safe to use a Seq and map
        // operations uniformly.
        type OutputValue = CacheValue // TODO specify this further if applicable
        val prevRdds: Seq[RDD[(OutputId, OutputLatency)]] = prevPerfRDDs(curr)
        
        val currCache: RDD[(OutputId, OutputValue)] = curr.lineageCache.withValueType[OutputValue]
        
        // Generalized method to handle case when either one or two dependencies are present.
        val aggTap = currTap.asInstanceOf[LatencyStatsTap[_]]
        val result = joinInputRddsWithAgg(
                                          aggTap,
                                          curr.numPartitions,
                                          currCache,
                                          prevRdds.head,
                                          prevRdds.lift(1)
        )
        PerfLineageCache.toPerfLineageCache(result)
      case _ if currTap.isEndOfStageTap =>
        // eg TapLRDD, PreShuffle, PreCoGroup
        // conveniently, we know there's only one input.
        type OutputValue = EndOfStageCacheValue
        val prevLatencyRdd: RDD[(InputId, InputLatency)] = prevPerfRDD(curr)
        
        val currRdd: RDD[(OutputId, OutputValue)] = curr.lineageCache.withValueType[OutputValue]
        debugPrint(currRdd, "EndOfStage lineage prior to join with prev latencies - " + currTap)
        // Arrange the output rdd (currRdd) so that it can be joined against the inputs.
        val inpToOutputWithPartialLatency: RDD[(InputId, ((OutputId, OutputValue), UnAccumulatedLatency))] =
          currRdd.flatMap(r => r._2.inputKeysWithPartialLatencies.map({
            case (inputId, partialLatency) => (inputId, (r, partialLatency))
          }))
        val joinResult: RDD[(InputId, (InputLatency, ((OutputId, OutputValue), UnAccumulatedLatency)))] =
        // TODO placeholder for future optimization - TapLRDDs should have 1-1 dependency on parents
          LineageWrapper.joinLineageKeyedRDDs(prevLatencyRdd, inpToOutputWithPartialLatency, useShuffle = false)
        
        // For TapLRDD, it's a 1-1 mapping so we only need to sum or otherwise accumulate latency.
        // No need to use the agg function.
        debugPrint(joinResult.sortBy(_._2._2._1._1), "EndOfStage join result (pre-accumulation, " +
          "may contain duplicates for shuffle-based RDDs) " + currTap)
        
        val accumulatedLatencies: RDD[(OutputId, (OutputValue, OutputLatency))] =
          currTap match {
            case aggTap: LatencyStatsTap[_] =>
              // There are multiple latencies (computed from inputs) for each output record. We
              // have to aggregate over all of them after accumulating partial latencies.
              val nonAggregatedLatencies: RDD[((OutputId, OutputValue), UnAccumulatedLatency)] =
                joinResult.values.map {
                  case(inputLatency, (outputRecord, partialOutputLatency)) =>
                    (outputRecord, accFn(inputLatency, partialOutputLatency))
                }
    
              val aggregatedLatenciesWithCounts: RDD[((OutputId, OutputValue), AggResultWithCount)] =
                nonAggregatedLatencies.reduceByKeyWithCount(aggFn)
              debugPrint(aggregatedLatenciesWithCounts, "EndOfStage LatencyStatsTap computed " +
                          "latencies post-aggregation, pre-shuffleStats: " + currTap)
              // TODO: In cases when there is no map-side combine, it is misleading to attribute
              // multiple inputs to the 'record' identified by the previous reduceByKey(agg)
              // operation. If no map-side combine exists, the # of outputs == # inputs.
              // Example scenario: groupByKey where a preshuffle partition consists only of 10
              // "foo"-keys. Each record technically contributes a fraction of the entire
              // partition latency, but the titian 'combined' lineage record will misleadingly
              // indicate otherwise.
              val result: RDD[(OutputId, (OutputValue, OutputLatency))] =
              // use curr.numPartitions because that's precomputed and saved, so no
              // post-serialization issues occur (vs using aggTap.getNumPartitions)
                addLatencyStats(aggregatedLatenciesWithCounts, aggTap, curr.numPartitions)
              result
            case _ =>
              // There's no need to aggregate latencies (ie reduce), since TapLRDDValues have a strict
              // single input (1-1) mapping. Simply rearrange the records to the right schema.
              joinResult.values.map {
                case (inputLatency, ((outputId, outputValue), partialOutputLatency)) =>
                  (outputId, (outputValue, accFn(inputLatency, partialOutputLatency)))
              }
          }
        
        // Finally, cast it since OutputValue is a bit more specific than the desired output and
        // Scala doesn't handle class hierarchy compatibility within tuples.
        PerfLineageCache.toPerfLineageCache(accumulatedLatencies)
      
      case _ =>
        // This technically should never be reached, but if it does we're in trouble.
        throw new UnsupportedOperationException("Performance tracing is not supported for " +
                                                  currTap)
    }
    /*debugPrint(resultWithoutShuffleStats.sortBy(_._2._2, ascending = false),
               currTap + " computed latencies, without shuffle stats (if applicable)")*/
    
    debugPrint(result.sortBy(_._2._2, ascending = false),
               currTap + " final computed latencies")
    result
  }
  
  private def addLatencyStats[OutputValue <: CacheValue](
                                 rdd: RDD[((OutputId, OutputValue), AggResultWithCount)],
                                 aggTap: LatencyStatsTap[_],
                                 numPartitions: Int
                                ): RDD[(OutputId, (OutputValue, UnAccumulatedLatency))] = {
    val shuffleAggStats = getRDDAggStats(aggTap, numPartitions)
    debugPrintStr(s"Shuffle agg stats for $aggTap: (input/output/latency)" +
                    s"\n\t${shuffleAggStats.mkString("\n\t")}")
    val shuffleAggStatsBroadcast: Broadcast[Map[PartitionId, AggregateLatencyStats]] =
      initWrapper.context.broadcast(shuffleAggStats)
  
    
    // Check if the aggTap is a preshuffle Tap where map-side combine is not enabled - if that's
    // the case, we want to ignore the number of inputs because Titian only produces one output
    // record per key+partition in the preshuffle phase, and there's a strict 1-1 mapping if
    // map-side combine has been disabled (ie we know there's only one input).
    val mapSideCombineDisabled = aggTap.isEndOfStageTap && !aggTap.isCombinerEnabled
    // If we knew this result was partitioned by outputId.partition already, then we could
    // run a more efficient mapPartitions call with only one map lookup.
    // TODO: check presence of partitioner??
    val result = rdd.map {
      case ((outputId, outputValue), aggWithCount) => {
        val aggStats: AggregateLatencyStats = shuffleAggStatsBroadcast.value(outputId.partition)
        val numInputs = if (mapSideCombineDisabled) 1 else aggWithCount.count
        val latencyWithoutShuffle = aggWithCount.aggResult
        // TODO: is precision loss a concern here?
        val shuffleLatency = aggStats.latency * numInputs / aggStats.numInputs //numOutputs unused
        (outputId, (outputValue, accFn(latencyWithoutShuffle, shuffleLatency)))
      }
    }
    result
  }
}

object PerfTraceCalculator {
  // Note: These could be generalized (generics) but are only used for this specific use case
  // right now
  private implicit class AggCountRDD[K](rdd: RDD[(K, Long)])(implicit keyTag: ClassTag[K]) {
    //implicit val ct = scala.reflect.classTag[AggResultWithCount]
    /** Mirrors reduceByKey including default partitioner */
    def reduceByKeyWithCount(fn: (Long, Long) => Long): RDD[(K, AggResultWithCount)] = {
      val (createCombinerAggCount, mergeValueAggCount, mergeCombinerAggCount) =
        createCombineFnsForReduce(fn)
      rdd.combineByKeyWithClassTag(createCombinerAggCount,
                                   mergeValueAggCount,
                                   mergeCombinerAggCount)
    }
    
    def reduceByKeyWithCount(part: Partitioner,
                             fn: (Long, Long) => Long): RDD[(K, AggResultWithCount)] = {
      val (createCombinerAggCount, mergeValueAggCount, mergeCombinerAggCount) =
        createCombineFnsForReduce(fn)
      rdd.combineByKeyWithClassTag(createCombinerAggCount,
                                   mergeValueAggCount,
                                   mergeCombinerAggCount,
                                   part)
    }
    
    private def createCombineFnsForReduce(fn: (Long, Long) => Long) = {
      // create V=> C - technically indep of fn
      val createCombinerAggCount = (v: Long) => AggResultWithCount(v, 1)
      // mergeV(C, V) => C
      val mergeValueAggCount = (c: AggResultWithCount, v: Long) => {
        c.aggResult = fn(c.aggResult, v)
        c.count += 1
        c
      }
      // mergeC(C, C) => C
      val mergeCombinerAggCount = (c1: AggResultWithCount, c2: AggResultWithCount) => {
        c1.mergeWith(c2, fn)
      }
      (createCombinerAggCount, mergeValueAggCount, mergeCombinerAggCount)
    }
    
  }
}

// Note: These could be generalized (generics) but are only used for this specific use case
// right now
private case class AggResultWithCount(var aggResult: Long, var count: Long) {
  def mergeWith(other: AggResultWithCount, mergeFn: (Long, Long) => Long): AggResultWithCount = {
    this.aggResult = mergeFn(this.aggResult, other.aggResult)
    this.count += other.count
    this
  }
}