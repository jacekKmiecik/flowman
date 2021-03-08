/*
 * Copyright 2018-2021 Kaya Kupferschmidt
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

package com.dimajix.flowman.spec.mapping

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.dimajix.flowman.execution.Session
import com.dimajix.flowman.model.Mapping
import com.dimajix.flowman.model.MappingIdentifier
import com.dimajix.flowman.model.MappingOutputIdentifier
import com.dimajix.flowman.model.Module
import com.dimajix.flowman.types.StructType
import com.dimajix.spark.testing.LocalSparkSession


class FilterMappingTest extends AnyFlatSpec with Matchers with LocalSparkSession {
    "An FilterMapping" should "be parseable" in {
        val spec =
            """
              |mappings:
              |  m1:
              |    kind: filter
              |    input: some_mapping
              |    condition: "value < 50"
            """.stripMargin

        val project = Module.read.string(spec).toProject("project")
        val session = Session.builder().withSparkSession(spark).build()
        val context = session.getContext(project)

        val mapping = project.mappings("m1")
        mapping shouldBe a[FilterMappingSpec]

        val instance = context.getMapping(MappingIdentifier("m1"))
        instance shouldBe a[FilterMapping]

        val filter = instance.asInstanceOf[FilterMapping]
        filter.input should be (MappingOutputIdentifier("some_mapping"))
        filter.outputs should be (Seq("main"))
        filter.condition should be ("value < 50")
    }

    it should "work" in {
        val session = Session.builder().withSparkSession(spark).build()
        val executor = session.execution

        val input = spark.range(100).toDF()
        val inputSchema = StructType.of(input.schema)

        val mapping = FilterMapping(
            Mapping.Properties(session.context),
            MappingOutputIdentifier("input"),
            "id < 50"
        )

        mapping.describe(executor, Map(MappingOutputIdentifier("input") -> inputSchema)) should be (Map("main" -> inputSchema))

        val result = mapping.execute(executor, Map(MappingOutputIdentifier("input") -> input))("main")
        result.count() should be (50)
    }
}