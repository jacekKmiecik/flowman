/*
 * Copyright 2018-2019 Kaya Kupferschmidt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimajix.flowman.spec.target

import java.util.Locale

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.streaming.OutputMode
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.MappingIdentifier
import com.dimajix.flowman.spec.RelationIdentifier


case class StreamTarget(
    instanceProperties:Target.Properties,
    relation:RelationIdentifier,
    mode:OutputMode,
    parallelism:Int,
    checkpointLocation:Path
) extends BaseTarget {
    private val logger = LoggerFactory.getLogger(classOf[StreamTarget])

    /**
      * Abstract method which will perform the output operation. All required tables need to be
      * registered as temporary tables in the Spark session before calling the execute method.
      *
      * @param executor
      */
    override def build(executor: Executor, tables: Map[MappingIdentifier, DataFrame]): Unit = {
        val target = this.relation
        val input = instanceProperties.input

        logger.info(s"Writing mapping '$input' to streaming relation '$target' using mode '$mode' and checkpoint location '$checkpointLocation'")
        val relation = context.getRelation(target)
        val table = tables(input).coalesce(parallelism)
        relation.writeStream(executor, table, mode, checkpointLocation)
    }

    /**
      * Clean up streaming target. Actually this method delegates the work to the relation target
      *
      * @param executor
      */
    override def clean(executor: Executor): Unit = {
        val target = this.relation

        logger.info(s"Cleaining streaming relation '$target'")
        val relation = context.getRelation(target)
        relation.clean(executor)
    }
}




class StreamTargetSpec extends TargetSpec {
    @JsonProperty(value="relation", required=true) private var relation:String = _
    @JsonProperty(value="mode", required=false) private var mode:String = OutputMode.Update().toString
    @JsonProperty(value="checkpointLocation", required=false) private var checkpointLocation:String = _
    @JsonProperty(value="parallelism", required=false) private var parallelism:String = "16"


    override def instantiate(context: Context): Target = {
        val  mode = context.evaluate(this.mode).toUpperCase(Locale.ROOT) match {
            case "APPEND" => OutputMode.Append()
            case "COMPLETE" => OutputMode.Complete()
            case "UPDATE" => OutputMode.Update()
            case mode:String => throw new IllegalArgumentException(s"Unsupported output mode '$mode'")
        }

        val checkpointLocation = Option(context.evaluate(this.checkpointLocation))
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse("/tmp/flowman-streaming-sink-" + name + "-" + System.currentTimeMillis())


        StreamTarget(
            instanceProperties(context),
            RelationIdentifier.parse(context.evaluate(relation)),
            mode,
            context.evaluate(parallelism).toInt,
            new Path(checkpointLocation)
        )
    }
}