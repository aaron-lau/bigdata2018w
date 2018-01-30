/**
  * Bespin: reference implementations of "big data" algorithms
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package ca.uwaterloo.cs451.a2

import io.bespin.scala.util.Tokenizer

import org.apache.log4j._
import org.apache.hadoop.fs._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.rogach.scallop._
import org.apache.spark.Partitioner

class myPartitionerPairs(partitionsNum: Int) extends Partitioner {
 override def numPartitions: Int = partitionsNum
 override def getPartition(key: Any): Int = {
    val k = key.asInstanceOf[String]
    return ( (k.split(", ").head.hashCode() & Integer.MAX_VALUE ) % numPartitions).toInt
  }
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  mainOptions = Seq(input, output, reducers)
  val input = opt[String](descr = "input path", required = true)
  val output = opt[String](descr = "output path", required = true)
  val reducers = opt[Int](descr = "number of reducers", required = false, default = Some(1))
  verify()
}

object ComputeBigramRelativeFrequencyPairs extends Tokenizer {
  val log = Logger.getLogger(getClass().getName())

  def main(argv: Array[String]) {
    val args = new Conf(argv)

    log.info("Input: " + args.input())
    log.info("Output: " + args.output())
    log.info("Number of reducers: " + args.reducers())

    val conf = new SparkConf().setAppName("ComputeBigramRelativeFrequencyPairs")
    val sc = new SparkContext(conf)

    val outputDir = new Path(args.output())
    FileSystem.get(sc.hadoopConfiguration).delete(outputDir, true)

    val textFile = sc.textFile(args.input())
    val counts = textFile
      .flatMap(line => {
        val tokens1 = tokenize(line)
        val tokens2 = tokenize(line)
        List (
          (if (tokens1.length > 1) tokens1.sliding(2).map(p => p.mkString(", ")).toList else List()),
          (if (tokens2.length > 1) tokens2.map(p => p + ", *" ).toList.dropRight(1) else List())
        ).flatten
      })
      .map(bigram => (bigram, 1))
      .reduceByKey(_ + _)
      .sortByKey()
      .partitionBy(new myPartitionerPairs(args.reducers()))
      .mapPartitions(occurrence => {
        var marginal = 0.0f
        occurrence.map(coccurrence => {
          if (coccurrence._1.split(", ")(1) == "*"){
              marginal = coccurrence._2.toFloat
              ("("+ coccurrence._1 +")", coccurrence._2)
          } else {
            ("("+ coccurrence._1 +")", coccurrence._2 / marginal)
          }
        })
      }

      )
    counts.saveAsTextFile(args.output())
  }
}
