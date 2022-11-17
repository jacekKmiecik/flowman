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

package com.dimajix.spark.sql.execution

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.SortOrder
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.UnaryExecNode
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.LongAccumulator


case class CountRecordsExec(child: SparkPlan, counter:LongAccumulator) extends UnaryExecNode {
    override def output: Seq[Attribute] = child.output

    override def outputPartitioning: Partitioning = child.outputPartitioning
    override def outputOrdering: Seq[SortOrder] = child.outputOrdering

    override protected def doCanonicalize(): SparkPlan = copy(child=child.canonicalized)

    override protected def doExecute(): RDD[InternalRow] = {
        val collector = counter
        child.execute().mapPartitions { rows =>
            val updater = new LongAccumulator
            TaskContext.get().addTaskCompletionListener[Unit] { _ =>
                collector.merge(updater)
            }

            rows.map { row =>
                updater.add(1)
                row
            }
        }
    }
}
