/*
 * Copyright 2021 Kaya Kupferschmidt
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

package com.dimajix.flowman.spec.assertion

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.DataFrame
import org.slf4j.LoggerFactory

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Execution
import com.dimajix.flowman.model.Assertion
import com.dimajix.flowman.model.AssertionResult
import com.dimajix.flowman.model.BaseAssertion
import com.dimajix.flowman.model.MappingOutputIdentifier
import com.dimajix.flowman.model.ResourceIdentifier
import com.dimajix.spark.sql.DataFrameUtils
import com.dimajix.spark.sql.SqlParser

object SqlAssertion {
    case class Case(
        sql:String,
        expected:Seq[Array[String]] = Seq()
    ) {
        override def hashCode(): Int = {
            (sql, expected.map(_.toSeq)).hashCode()
        }

        override def equals(obj: Any): Boolean = {
            if (obj == null) {
                false
            }
            else if (super.equals(obj)) {
                true
            }
            else if (!obj.isInstanceOf[Case]) {
                false
            }
            else {
                val otherCase = obj.asInstanceOf[Case]
                val l = (sql, expected.map(_.toSeq))
                val r = (otherCase.sql, otherCase.expected.map(_.toSeq))
                l == r
            }
        }
    }
}
case class SqlAssertion(
    override val instanceProperties:Assertion.Properties,
    tests: Seq[SqlAssertion.Case]
) extends BaseAssertion {
    private val logger = LoggerFactory.getLogger(classOf[SqlAssertion])

    /**
     * Returns a list of physical resources required by this assertion. This list will only be non-empty for assertions
     * which actually read from physical data.
     *
     * @return
     */
    override def requires: Set[ResourceIdentifier] = Set()

    /**
     * Returns the dependencies (i.e. names of tables in the Dataflow model)
     *
     * @return
     */
    override def inputs: Seq[MappingOutputIdentifier] = {
        tests.flatMap(test => SqlParser.resolveDependencies(test.sql))
            .map(MappingOutputIdentifier.parse)
            .distinct
    }

    /**
     * Executes this [[Assertion]] and returns a corresponding DataFrame
     *
     * @param execution
     * @param input
     * @return
     */
    override def execute(execution: Execution, input: Map[MappingOutputIdentifier, DataFrame]): Seq[AssertionResult] =  {
        require(execution != null)
        require(input != null)

        // Register all input DataFrames as temp views
        input.foreach(kv => kv._2.createOrReplaceTempView(kv._1.name))

        val results = tests.map { test =>
            // Execute query
            val sql = test.sql
            val actual = execution.spark.sql(sql)

            val result = DataFrameUtils.diffToStringValues(test.expected, actual)
            result match {
                case Some(diff) =>
                    logger.error(s"Difference between datasets: \n${diff}")
                    AssertionResult(sql, false)
                case None =>
                    AssertionResult(sql, true)
            }
        }

        // Call SessionCatalog.dropTempView to avoid unpersisting the possibly cached dataset.
        input.foreach(kv => execution.spark.sessionState.catalog.dropTempView(kv._1.name))

        results
    }
}


object SqlAssertionSpec {
    class Case {
        @JsonProperty(value="sql", required=true) private var sql:String = ""
        @JsonProperty(value="expected", required=true) private var expected:Seq[Array[String]] = Seq()

        def instantiate(context:Context) : SqlAssertion.Case = {
            SqlAssertion.Case(
                context.evaluate(sql),
                expected.map(_.map(context.evaluate))
            )
        }
    }
}
class SqlAssertionSpec extends AssertionSpec {
    @JsonProperty(value="tests", required=true) private var tests:Seq[SqlAssertionSpec.Case] = Seq()

    override def instantiate(context: Context): SqlAssertion = {
        SqlAssertion(
            instanceProperties(context),
            tests.map(_.instantiate(context))
        )
    }
}