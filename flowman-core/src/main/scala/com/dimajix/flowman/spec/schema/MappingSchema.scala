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

package com.dimajix.flowman.spec.schema

import scala.collection.mutable

import com.fasterxml.jackson.annotation.JsonProperty

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.spec.Instance
import com.dimajix.flowman.spec.MappingIdentifier
import com.dimajix.flowman.types.Field
import com.dimajix.flowman.types.StructType


object MappingSchema {
    def apply(context:Context, mapping:String) : MappingSchema = {
        MappingSchema(Schema.Properties(context), MappingIdentifier(mapping))
    }
}


case class MappingSchema (
    instanceProperties:Schema.Properties,
    mapping: MappingIdentifier
) extends Schema {
    /**
      * Returns the description of the schema
      * @return
      */
    override def description : String = s"Inferred from mapping $mapping"

    /**
      * Returns the list of all fields of the schema
      * @return
      */
    override def fields : Seq[Field] = {
        val schemaCache = mutable.Map[MappingIdentifier, StructType]()

        def describe(mapping:MappingIdentifier) : StructType = {
            schemaCache.getOrElseUpdate(mapping, {
                val map = context.getMapping(mapping)
                val deps = map.dependencies.map(id => (id,describe(id))).toMap
                map.describe(deps)
            })
        }

        describe(mapping).fields
    }

    /**
      * Returns the list of primary keys. Can be empty of no PK is available
      * @return
      */
    override def primaryKey : Seq[String] = Seq()
}



class MappingSchemaSpec extends SchemaSpec {
    @JsonProperty(value = "mapping", required = true) private var mapping: String = ""

    override def instantiate(context: Context): Schema = {
        MappingSchema(
            Schema.Properties(context),
            MappingIdentifier(context.evaluate(mapping))
        )
    }
}