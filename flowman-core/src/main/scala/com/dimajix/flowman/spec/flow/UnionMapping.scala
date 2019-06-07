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

package com.dimajix.flowman.spec.flow

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.DataFrame
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.MappingOutputIdentifier
import com.dimajix.flowman.spec.schema.Schema
import com.dimajix.flowman.spec.schema.SchemaSpec
import com.dimajix.flowman.transforms.SchemaEnforcer
import com.dimajix.flowman.types.StructType
import com.dimajix.flowman.util.SchemaUtils


case class UnionMapping(
    instanceProperties:Mapping.Properties,
    inputs:Seq[MappingOutputIdentifier],
    schema:Option[Schema],
    distinct:Boolean
) extends BaseMapping {
    private val logger = LoggerFactory.getLogger(classOf[UnionMapping])

    /**
      * Creates the list of required dependencies
      *
      * @return
      */
    override def dependencies : Seq[MappingOutputIdentifier] = {
        inputs
    }

    /**
      * Executes this MappingType and returns a corresponding DataFrame
      *
      * @param executor
      * @param tables
      * @return
      */
    override def execute(executor:Executor, tables:Map[MappingOutputIdentifier,DataFrame]) : Map[String,DataFrame] = {
        require(executor != null)
        require(tables != null)

        val dfs = inputs.map(tables(_))

        // Create a common schema from collected columns
        val schema = this.schema.map(_.sparkSchema).getOrElse(getCommonSchema(dfs))
        logger.info(s"Creating union from mappings ${inputs.mkString(",")} using columns ${schema.fields.map(_.name).mkString(",")}}")

        // Project all tables onto common schema
        val schemaEnforcer = SchemaEnforcer(schema)
        val projectedTables = dfs.map(schemaEnforcer.transform)

        // Now create a union of all tables
        val union = projectedTables.reduce((l,r) => l.union(r))

        // Optionally perform distinct operation
        val result = if (distinct)
                union.distinct()
            else
                union

        Map("main" -> result)
    }

    override def describe(input: Map[MappingOutputIdentifier, StructType]): Map[String, StructType] = {
        require(input != null)

        val result = schema
            .map(s => StructType(s.fields))
            .getOrElse {
                val schemas = input.values.map(_.sparkType).toSeq
                StructType.of(SchemaUtils.union(schemas))
            }

        Map("main" -> result)
    }

    private def getCommonSchema(tables:Seq[DataFrame]) = {
        SchemaUtils.union(tables.map(_.schema))
    }
}



class UnionMappingSpec extends MappingSpec {
    @JsonProperty(value="inputs", required=true) var inputs:Seq[String] = Seq()
    @JsonProperty(value="schema", required=false) var schema:SchemaSpec = _
    @JsonProperty(value="distinct", required=false) var distinct:String = "false"

    /**
      * Creates the instance of the specified Mapping with all variable interpolation being performed
      * @param context
      * @return
      */
    override def instantiate(context: Context): UnionMapping = {
        UnionMapping(
            instanceProperties(context),
            inputs.map(i => MappingOutputIdentifier.parse(context.evaluate(i))),
            Option(schema).map(_.instantiate(context)),
            context.evaluate(distinct).toBoolean
        )
    }
}
