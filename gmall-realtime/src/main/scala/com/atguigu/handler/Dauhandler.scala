package com.atguigu.handler

import java.{lang, util}
import java.text.SimpleDateFormat
import java.util.Date

import com.atguigu.bean.StartUpLog
import com.atguigu.utils.RedisUtil
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.DStream
import redis.clients.jedis.Jedis

object Dauhandler {
  /**
    * 批次内去重
    * @param fileterByRedisDStream
    */
  def filterByMid(fileterByRedisDStream: DStream[StartUpLog]) = {
    //1.转换数据结构（（mid，logdate），StartUpLog）
    val midToLogDStream: DStream[((String, String), StartUpLog)] = fileterByRedisDStream.map(log => {
      ((log.mid, log.logDate), log)
    })

    //2.使用groupByKey将相同key聚合到同一分区
    val midToLogIterDStream: DStream[((String, String), Iterable[StartUpLog])] = midToLogDStream.groupByKey()

    //3.排序取出第一条数据，达到去重的效果
    val midToLogList: DStream[((String, String), List[StartUpLog])] = midToLogIterDStream.mapValues(iter => {
      iter.toList.sortWith(_.ts < _.ts).take(1)
    })

    //4.扁平化
    val result: DStream[StartUpLog] = midToLogList.flatMap(_._2)
    result
  }

  /**
    * 跨批次去重
    *
    * @param startUpLogDStream
    */
  def filterByRedis(startUpLogDStream: DStream[StartUpLog],sc:SparkContext) = {

    //    //方案一
    //    val value: DStream[StartUpLog] = startUpLogDStream.filter(log => {
    //
    //      //1.获取Redis连接
    //      val jedisClient: Jedis = RedisUtil.getJedisClient
    //
    //      //2.过滤数据
    //      val redisKey = "DAU:" + log.logDate
    //      val boolean: lang.Boolean = jedisClient.sismember(redisKey, log.mid)
    //
    //      //3.归还连接
    //      jedisClient.close()
    //      !boolean
    //    })
    //    value
    //方案二(在分区下获取连接，减少连接个数)
//    val value2: DStream[StartUpLog] = startUpLogDStream.mapPartitions(partition => {
//      //1.获取Redis连接
//      val jedisClient: Jedis = RedisUtil.getJedisClient
//      //2.过滤数据
//      partition.filter(log => {
//        //2.过滤数据
//        val redisKey = "DAU:" + log.logDate
//        val boolean: lang.Boolean = jedisClient.sismember(redisKey, log.mid)
//        !boolean
//      })
//      //3.归还连接
//      jedisClient.close()
//      partition
//    })
//    value2
    //方案三（每个批次获取一次连接）
    val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd")
    startUpLogDStream.transform(rdd=>{
      //1.获取连接
      val jedisClient: Jedis = RedisUtil.getJedisClient

      //2.获取数据
      val redisKey="DAU:" + sdf.format(new Date(System.currentTimeMillis()))
      val midSet: util.Set[String] = jedisClient.smembers(redisKey)

      //3.归还连接
      jedisClient.close()

      //4.广播数据
      val midBC: Broadcast[util.Set[String]] = sc.broadcast(midSet)

      //5.过滤数据
      val result: RDD[StartUpLog] = rdd.filter(log => {
        !midBC.value.contains(log.mid)
      })
      result
    })
  }

  /**
    * 将去重后的mid写入redis
    *
    * @param startUpLogDStream
    */
  def saveMidToRedis(startUpLogDStream: DStream[StartUpLog]) = {
    startUpLogDStream.foreachRDD(rdd => {

      rdd.foreachPartition(partition => {
        //1.获取连接
        val jedisClient: Jedis = RedisUtil.getJedisClient
        partition.foreach(log => {
          //2.写库
          val redisKey = "DAU:" + log.logDate
          jedisClient.sadd(redisKey, log.mid)
        })
        //3.归还连接
        jedisClient.close()
      })
    })
  }
}
