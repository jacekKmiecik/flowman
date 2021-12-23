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

package com.dimajix.flowman.jdbc

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.functions.expr
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.dimajix.flowman.catalog.PartitionSpec
import com.dimajix.flowman.execution.DeleteClause
import com.dimajix.flowman.execution.InsertClause
import com.dimajix.flowman.execution.UpdateClause
import com.dimajix.flowman.types.Field
import com.dimajix.flowman.util.UtcTimestamp


class BaseDialectTest extends AnyFlatSpec with Matchers {
    "The BaseDialect" should "create PARTITION specs" in {
        val dialect = NoopDialect
        val partitionSpec = PartitionSpec(Map(
            "p1" -> "lala",
            "p2" -> 12,
            "p3" -> java.sql.Date.valueOf("2021-08-03")
        ))
        dialect.expr.partition(partitionSpec) should be ("""PARTITION(p1='lala',p2=12,p3='2021-08-03')""")
    }

    it should "provide appropriate IN expression" in {
        val dialect = NoopDialect
        dialect.expr.in("col", Seq()) should be (""""col" IN ()""")
        dialect.expr.in("col", Seq(1)) should be (""""col" IN (1)""")
        dialect.expr.in("col", Seq(1,7)) should be (""""col" IN (1,7)""")
        dialect.expr.in("col", Seq("left","right")) should be (""""col" IN ('left','right')""")
        dialect.expr.in("col", Seq(java.sql.Date.valueOf("2021-08-03"))) should be (""""col" IN (date('2021-08-03'))""")
        dialect.expr.in("col", Seq(UtcTimestamp.parse("2021-08-03T02:03:44"))) should be (""""col" IN (timestamp(1627956224))""")
    }

    it should "provide CREATE statements" in {
        val dialect = NoopDialect
        val table = TableIdentifier("table_1", Some("my_db"))
        val tableDefinition = TableDefinition(
            table,
            Seq(
                Field("id", com.dimajix.flowman.types.IntegerType, nullable = false),
                Field("name", com.dimajix.flowman.types.StringType),
                Field("sex", com.dimajix.flowman.types.StringType)
            )
        )

        val sql = dialect.statement.createTable(tableDefinition)
        sql should be (
            """CREATE TABLE "my_db"."table_1" (
              |    "id" INTEGER NOT NULL,
              |    "name" TEXT,
              |    "sex" TEXT
              |)
              |""".stripMargin)
    }

    it should "provide CREATE statements with PK" in {
        val dialect = NoopDialect
        val table = TableIdentifier("table_1", Some("my_db"))
        val tableDefinition = TableDefinition(
            table,
            Seq(
                Field("id", com.dimajix.flowman.types.IntegerType, nullable = false),
                Field("name", com.dimajix.flowman.types.StringType),
                Field("sex", com.dimajix.flowman.types.StringType)
            ),
            primaryKey = Seq("id")
        )

        val sql = dialect.statement.createTable(tableDefinition)
        sql should be (
            """CREATE TABLE "my_db"."table_1" (
              |    "id" INTEGER NOT NULL,
              |    "name" TEXT,
              |    "sex" TEXT,
              |    PRIMARY KEY ("id")
              |)
              |""".stripMargin)
    }

    it should "provide MERGE statements with complex clauses" in {
        val dialect = NoopDialect
        val table = TableIdentifier("table_1", Some("my_db"))
        val tableSchema = StructType(Seq(
            StructField("id", IntegerType),
            StructField("name", StringType),
            StructField("sex", StringType),
            StructField("state", StringType)
        ))
        val sourceSchema = StructType(Seq(
            StructField("id", IntegerType),
            StructField("name", StringType),
            StructField("sex", StringType),
            StructField("op", StringType)
        ))
        val condition = expr("source.id = target.id")
        val clauses = Seq(
            InsertClause(
                condition = Some(expr("source.op = 'INSERT' AND target.state <> 'FINAL'"))
            ),
            InsertClause(
                condition = Some(expr("source.op = 'INSERT'")),
                columns = Map("id" -> expr("source.id"), "name" -> expr("source.name"))
            ),
            DeleteClause(
                condition = Some(expr("source.op = 'DELETE'"))
            ),
            UpdateClause(
                columns = Map("name" -> expr("source.name"), "sex" -> expr("source.sex"))
            )
        )
        val sql = dialect.statement.merge(table, "target", Some(tableSchema), "source", sourceSchema, condition, clauses)
        sql should be (
            """MERGE INTO "my_db"."table_1" target
              |USING (VALUES(?,?,?,?)) source(id,name,sex,op)
              |ON (source."id" = target."id")
              |WHEN NOT MATCHED AND ((source."op" = 'INSERT') AND (NOT (target."state" = 'FINAL'))) THEN INSERT("id","name","sex") VALUES(source."id",source."name",source."sex")
              |WHEN NOT MATCHED AND (source."op" = 'INSERT') THEN INSERT("id","name") VALUES(source."id",source."name")
              |WHEN MATCHED AND (source."op" = 'DELETE') THEN DELETE
              |WHEN MATCHED THEN UPDATE SET "name" = source."name", "sex" = source."sex"
              |;""".stripMargin)
    }

    it should "provide MERGE statements with trivial clauses" in {
        val dialect = NoopDialect
        val table = TableIdentifier("table_1", Some("my_db"))
        val tableSchema = StructType(Seq(
            StructField("id", IntegerType),
            StructField("name", StringType),
            StructField("sex", StringType)
        ))
        val sourceSchema = StructType(Seq(
            StructField("id", IntegerType),
            StructField("name", StringType),
            StructField("sex", StringType)
        ))
        val condition = expr("source.id = target.id")
        val clauses = Seq(
            InsertClause(),
            UpdateClause()
        )
        val sql = dialect.statement.merge(table, "target", Some(tableSchema), "source", sourceSchema, condition, clauses)
        sql should be (
            """MERGE INTO "my_db"."table_1" target
              |USING (VALUES(?,?,?)) source(id,name,sex)
              |ON (source."id" = target."id")
              |WHEN NOT MATCHED THEN INSERT("id","name","sex") VALUES(source."id",source."name",source."sex")
              |WHEN MATCHED THEN UPDATE SET "id" = source."id", "name" = source."name", "sex" = source."sex"
              |;""".stripMargin)
    }
}
