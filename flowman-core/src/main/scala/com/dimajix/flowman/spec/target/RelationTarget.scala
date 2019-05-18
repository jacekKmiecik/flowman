/*
 * Copyright 2018 Kaya Kupferschmidt
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

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.DataFrame
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.MappingIdentifier
import com.dimajix.flowman.spec.RelationIdentifier
import com.dimajix.flowman.state.TargetInstance
import com.dimajix.flowman.types.SingleValue


case class RelationTarget(
    instanceProperties: Target.Properties,
    relation: RelationIdentifier,
    mode: String,
    partition: Map[String,String],
    parallelism: Int,
    rebalance: Boolean
) extends BaseTarget {
    private val logger = LoggerFactory.getLogger(classOf[RelationTarget])

    /**
      * Returns an instance representing this target with the context
      * @return
      */
    override def instance : TargetInstance = {
        TargetInstance(
            Option(context.namespace).map(_.name).getOrElse(""),
            Option(context.project).map(_.name).getOrElse(""),
            name,
            partition
        )
    }

    /**
      * Builds the target using the given input tables
      *
      * @param executor
      * @param tables
      */
    override def build(executor:Executor, tables:Map[MappingIdentifier,DataFrame]) : Unit = {
        val partition = this.partition.mapValues(v => SingleValue(v))
        val input = instanceProperties.input

        logger.info(s"Writing mapping '$input' to relation '${this.relation}' into partition $partition")
        val relation = context.getRelation(this.relation)
        val table = if (rebalance)
            tables(input).repartition(parallelism)
        else
            tables(input).coalesce(parallelism)

        relation.write(executor, table, partition, mode)
    }

    /**
      * Cleans the target. This will remove any data in the target for the current partition
      * @param executor
      */
    override def clean(executor: Executor): Unit = {
        val partition = this.partition.mapValues(v => SingleValue(v))

        logger.info(s"Cleaning partition $partition of relation '${this.relation}'")
        val relation = context.getRelation(this.relation)
        relation.clean(executor, partition)
    }
}




class RelationTargetSpec extends TargetSpec {
    @JsonProperty(value="relation", required=true) private var _relation:String = _
    @JsonProperty(value="mode", required=false) private var _mode:String = "overwrite"
    @JsonProperty(value="partition", required=false) private var _partition:Map[String,String] = Map()
    @JsonProperty(value="parallelism", required=false) private var _parallelism:String = "16"
    @JsonProperty(value="rebalance", required=false) private var _rebalance:String = "false"

    override def instantiate(context: Context): Target = {
        RelationTarget(
            instanceProperties(context),
            RelationIdentifier.parse(context.evaluate(_relation)),
            context.evaluate(_mode),
            _partition.mapValues(context.evaluate),
            context.evaluate(_parallelism).toInt,
            context.evaluate(_rebalance).toBoolean
        )
    }
}