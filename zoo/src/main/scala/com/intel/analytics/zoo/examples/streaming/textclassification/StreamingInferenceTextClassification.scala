/*
 * Copyright 2018 Analytics Zoo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.zoo.examples.streaming.textclassification

import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.feature.text.{TextFeature, TextSet}
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric.NumericFloat
import com.intel.analytics.zoo.pipeline.inference.InferenceModel
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.{Seconds, StreamingContext}
import scopt.OptionParser


object StreamingInferenceTextClassification {

  def main(args: Array[String]) {
    val parser = new OptionParser[TextClassificationParams]("Streaming Text Classification") {
      opt[String]('h', "host")
        .text("host for network connection")
        .action((x, c) => c.copy(host = x))
      opt[Int]('p', "port")
        .text("Port for network connection")
        .action((x, c) => c.copy(port = x))
      opt[String]("indexPath")
        .text("Path of word to index text file")
        .action((x, c) => c.copy(indexPath = x))
        .required()
      opt[Int]("partitionNum")
        .text("The number of partitions to cut the dataset into")
        .action((x, c) => c.copy(partitionNum = x))
      opt[Int]("sequenceLength")
        .text("The length of each sequence")
        .action((x, c) => c.copy(sequenceLength = x))
      opt[Int]('b', "batchSize")
        .text("The number of samples per gradient update")
        .action((x, c) => c.copy(batchSize = x))
      opt[String]('m', "model")
        .text("Path of pre-trained Model")
        .action((x, c) => c.copy(model = x))
        .required()
      opt[String]('f', "inputFile")
        .text("The file of input text")
        .action((x, c) => c.copy(inputFile = x))
    }


    parser.parse(args, TextClassificationParams()).map { param =>
      val sc = NNContext.initNNContext("Analytics Zoo Streaming Text Classification")
      val ssc = new StreamingContext(sc, Seconds(3))

      // Load pre-trained bigDL model
      val model = new InferenceModel(4)
      model.doLoad(param.model)

      // Labels of 20 Newsgroup dataset
      val labels = Array("alt.atheism",
        "comp.graphics",
        "comp.os.ms-windows.misc",
        "comp.sys.ibm.pc.hardware",
        "comp.sys.mac.hardware",
        "comp.windows.x",
        "misc.forsale",
        "rec.autos",
        "rec.motorcycles",
        "rec.sport.baseball",
        "rec.sport.hockey",
        "sci.crypt",
        "sci.electronics",
        "sci.med",
        "sci.space",
        "soc.religion.christian",
        "talk.politics.guns",
        "talk.politics.mideast",
        "talk.politics.misc",
        "talk.religion.misc")


      // Create a socket stream on target ip:port and count the
      // words in input stream of \n delimited text (eg. generated by 'nc')
      // Note that no duplication in storage level only for running locally.
      // Replication necessary in distributed scenario for fault tolerance.
      val lines = if (param.inputFile.isEmpty) {
      ssc.socketTextStream(param.host, param.port, StorageLevel.MEMORY_AND_DISK_SER) }
      else { ssc.textFileStream(param.inputFile) }

      lines.foreachRDD { lineRdd =>
        if (!lineRdd.partitions.isEmpty) {
          // RDD to TextFeature
          val textFeature = lineRdd.map(x => TextFeature.apply(x))
          // RDD[TextFeature] to TextSet
          val dataSet = TextSet.rdd(textFeature)
          dataSet.loadWordIndex(param.indexPath)
          // Pre-processing
          val transformed = dataSet.tokenize().normalize()
            .word2idx()
            .shapeSequence(param.sequenceLength).generateSample()
          transformed.toDistributed().rdd.foreach { text =>
            val tensor = text.getSample.feature()
            // Add one more dim because of batch requirement of model
            val predictSet = model.doPredict(tensor.addSingletonDimension())
            println("Probability distributions of top-5:")
            predictSet.toTensor.select(1, 1).toArray
              .zipWithIndex.sortBy(_._1).reverse.slice(0, 5)
              .foreach(t => println(s"${labels(t._2)} ${t._1}"))
          }
        }
      }
      ssc.start()
      ssc.awaitTermination()
    }
  }
}
