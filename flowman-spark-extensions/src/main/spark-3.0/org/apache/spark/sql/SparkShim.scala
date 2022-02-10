/*
 * Copyright 2018-2022 Kaya Kupferschmidt
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

package org.apache.spark.sql

import java.util.TimeZone

import org.apache.spark.SparkConf
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.config.ConfigEntry
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
import org.apache.spark.sql.catalyst.analysis.NoSuchDatabaseException
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException
import org.apache.spark.sql.catalyst.analysis.ViewType
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.catalog.SessionCatalog
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.NamedExpression
import org.apache.spark.sql.catalyst.plans.logical.GroupingSets
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.util.IntervalUtils
import org.apache.spark.sql.execution.QueryExecution
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.columnar.InMemoryRelation
import org.apache.spark.sql.execution.command.AlterViewAsCommand
import org.apache.spark.sql.execution.command.CreateViewCommand
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.execution.datasources.FileFormat
import org.apache.spark.sql.execution.datasources.v2.FileDataSourceV2
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.RelationProvider
import org.apache.spark.sql.sources.SchemaRelationProvider
import org.apache.spark.unsafe.types.CalendarInterval
import org.apache.spark.unsafe.types.UTF8String


object SparkShim {
    def getHadoopConf(sparkConf:SparkConf) :org.apache.hadoop.conf.Configuration = SparkHadoopUtil.get.newConfiguration(sparkConf)

    def parseCalendarInterval(str:String) : CalendarInterval = IntervalUtils.stringToInterval(UTF8String.fromString(str))

    def calendarInterval(months:Int, days:Int, microseconds:Long=0L) : CalendarInterval = {
        new CalendarInterval(months, days, microseconds)
    }

    def millisToDays(millisUtc: Long, timeZone: TimeZone): Int = {
        org.apache.spark.sql.catalyst.util.DateTimeUtils.millisToDays(millisUtc, timeZone.toZoneId)
    }

    def isStaticConf(key:String) : Boolean = {
        SQLConf.staticConfKeys.contains(key) ||
            (ConfigEntry.findEntry(key) != null && !SQLConf.sqlConfEntries.containsKey(key))
    }

    def relationSupportsMultiplePaths(spark:SparkSession, format:String) : Boolean = {
        val providingClass = DataSource.lookupDataSource(format, spark.sessionState.conf)
        relationSupportsMultiplePaths(providingClass)
    }

    def relationSupportsMultiplePaths(providingClass:Class[_]) : Boolean = {
        providingClass.getDeclaredConstructor().newInstance() match {
            case _: RelationProvider => false
            case _: SchemaRelationProvider => false
            case _: FileFormat => true
            case _: FileDataSourceV2 => true
            case _ => false
        }
    }

    def groupingSetAggregate(
        groupByExpressions:Seq[Expression],
        groupingSets:Seq[Seq[Expression]],
        aggregateExpressions: Seq[NamedExpression],
        child: LogicalPlan) : LogicalPlan = {
        GroupingSets(
            groupingSets,
            groupByExpressions,
            child,
            aggregateExpressions
        )
    }

    def withNewExecutionId[T](
        sparkSession: SparkSession,
        queryExecution: QueryExecution,
        name: Option[String] = None)(body: => T): T =
        SQLExecution.withNewExecutionId(queryExecution, name)(body)

    def functionRegistry(spark:SparkSession) : FunctionRegistry = spark.sessionState.functionRegistry

    def createView(table:TableIdentifier, select:String, plan:LogicalPlan, allowExisting:Boolean, replace:Boolean) : CreateViewCommand = {
        CreateViewCommand(table, Nil, None, Map(), Some(select), plan, allowExisting, replace, SparkShim.PersistedView)
    }
    def alterView(table:TableIdentifier, select:String, plan:LogicalPlan, allowExisting:Boolean, replace:Boolean) : AlterViewAsCommand = {
        AlterViewAsCommand(table, select, plan)
    }

    val LocalTempView : ViewType = org.apache.spark.sql.catalyst.analysis.LocalTempView
    val GlobalTempView : ViewType = org.apache.spark.sql.catalyst.analysis.GlobalTempView
    val PersistedView : ViewType = org.apache.spark.sql.catalyst.analysis.PersistedView
}
