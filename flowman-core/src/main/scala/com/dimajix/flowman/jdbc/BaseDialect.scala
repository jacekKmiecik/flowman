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

import java.sql.Date
import java.sql.JDBCType
import java.sql.SQLException
import java.util.Locale

import org.apache.commons.lang3.StringUtils
import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.jdbc.JdbcType
import org.apache.spark.sql.types.StructType

import com.dimajix.common.MapIgnoreCase
import com.dimajix.flowman.catalog.PartitionSpec
import com.dimajix.flowman.catalog.TableChange
import com.dimajix.flowman.catalog.TableChange.AddColumn
import com.dimajix.flowman.catalog.TableChange.DropColumn
import com.dimajix.flowman.catalog.TableChange.UpdateColumnComment
import com.dimajix.flowman.catalog.TableChange.UpdateColumnNullability
import com.dimajix.flowman.catalog.TableChange.UpdateColumnType
import com.dimajix.flowman.execution.DeleteClause
import com.dimajix.flowman.execution.InsertClause
import com.dimajix.flowman.execution.MergeClause
import com.dimajix.flowman.execution.UpdateClause
import com.dimajix.flowman.types.BinaryType
import com.dimajix.flowman.types.BooleanType
import com.dimajix.flowman.types.ByteType
import com.dimajix.flowman.types.CharType
import com.dimajix.flowman.types.DateType
import com.dimajix.flowman.types.DecimalType
import com.dimajix.flowman.types.DoubleType
import com.dimajix.flowman.types.FieldType
import com.dimajix.flowman.types.FloatType
import com.dimajix.flowman.types.IntegerType
import com.dimajix.flowman.types.LongType
import com.dimajix.flowman.types.ShortType
import com.dimajix.flowman.types.StringType
import com.dimajix.flowman.types.TimestampType
import com.dimajix.flowman.types.VarcharType
import com.dimajix.flowman.util.UtcTimestamp
import com.dimajix.spark.sql.expressions.UnresolvableExpression


abstract class BaseDialect extends SqlDialect {
    private object Statements extends BaseStatements(this) { }
    private object Expressions extends BaseExpressions(this) { }

    /**
      * Retrieve the jdbc / sql type for a given datatype.
      * @param dt The datatype (e.g. [[org.apache.spark.sql.types.StringType]])
      * @return The new JdbcType if there is an override for this DataType
      */
    override def getJdbcType(dt: FieldType): JdbcType = {
        dt match {
            case IntegerType => JdbcType("INTEGER", java.sql.Types.INTEGER)
            case LongType => JdbcType("BIGINT", java.sql.Types.BIGINT)
            case DoubleType => JdbcType("DOUBLE PRECISION", java.sql.Types.DOUBLE)
            case FloatType => JdbcType("REAL", java.sql.Types.FLOAT)
            case ShortType => JdbcType("INTEGER", java.sql.Types.SMALLINT)
            case ByteType => JdbcType("BYTE", java.sql.Types.TINYINT)
            case BooleanType => JdbcType("BIT(1)", java.sql.Types.BIT)
            case StringType => JdbcType("TEXT", java.sql.Types.CLOB)
            case v : CharType => JdbcType(s"CHAR(${v.length})", java.sql.Types.CHAR)
            case v : VarcharType => JdbcType(s"VARCHAR(${v.length})", java.sql.Types.VARCHAR)
            case BinaryType => JdbcType("BLOB", java.sql.Types.BLOB)
            case TimestampType => JdbcType("TIMESTAMP", java.sql.Types.TIMESTAMP)
            case DateType => JdbcType("DATE", java.sql.Types.DATE)
            case t: DecimalType => JdbcType(s"DECIMAL(${t.precision},${t.scale})", java.sql.Types.DECIMAL)
            case _ => throw new SQLException(s"Unsupported type ${dt.typeName}")
        }
    }

    /**
     * Maps a JDBC type to a Flowman type.  This function is called only when
     * the JdbcDialect class corresponding to your database driver returns null.
     *
     * @param sqlType - A field of java.sql.Types
     * @return The Flowman type corresponding to sqlType.
     */
    def getFieldType(sqlType: Int, typeName:String, precision: Int, scale: Int, signed: Boolean): FieldType = {
        sqlType match {
            //case java.sql.Types.ARRAY         => null
            case java.sql.Types.BIGINT        => if (signed) { LongType } else { DecimalType(20,0) }
            case java.sql.Types.BINARY        => BinaryType
            case java.sql.Types.BIT           => BooleanType // @see JdbcDialect for quirks
            case java.sql.Types.BLOB          => BinaryType
            case java.sql.Types.BOOLEAN       => BooleanType
            case java.sql.Types.CHAR          => CharType(precision)
            case java.sql.Types.CLOB          => StringType
            //case java.sql.Types.DATALINK      => null
            case java.sql.Types.DATE          => DateType
            case java.sql.Types.DECIMAL
                if precision != 0 || scale != 0 => DecimalType.bounded(precision, scale)
            case java.sql.Types.DECIMAL       => DecimalType.SYSTEM_DEFAULT
            //case java.sql.Types.DISTINCT      => null
            case java.sql.Types.DOUBLE        => DoubleType
            case java.sql.Types.FLOAT         => FloatType
            case java.sql.Types.INTEGER       => if (signed) { IntegerType } else { LongType }
            //case java.sql.Types.JAVA_OBJECT   => null
            case java.sql.Types.LONGNVARCHAR  => StringType
            case java.sql.Types.LONGVARBINARY => BinaryType
            case java.sql.Types.LONGVARCHAR   => StringType
            case java.sql.Types.NCHAR         => CharType(precision)
            case java.sql.Types.NCLOB         => StringType
            //case java.sql.Types.NULL          => null
            case java.sql.Types.NUMERIC
                if precision != 0 || scale != 0 => DecimalType.bounded(precision, scale)
            case java.sql.Types.NUMERIC       => DecimalType.SYSTEM_DEFAULT
            case java.sql.Types.NVARCHAR      => VarcharType(precision)
            //case java.sql.Types.OTHER         => null
            case java.sql.Types.REAL          => DoubleType
            case java.sql.Types.REF           => StringType
            //case java.sql.Types.REF_CURSOR    => null
            case java.sql.Types.ROWID         => LongType
            case java.sql.Types.SMALLINT      => IntegerType
            case java.sql.Types.SQLXML        => StringType
            case java.sql.Types.STRUCT        => StringType
            case java.sql.Types.TIME          => TimestampType
            //case java.sql.Types.TIME_WITH_TIMEZONE  => null
            case java.sql.Types.TIMESTAMP     => TimestampType
            //case java.sql.Types.TIMESTAMP_WITH_TIMEZONE  => null
            case java.sql.Types.TINYINT       => IntegerType
            case java.sql.Types.VARBINARY     => BinaryType
            case java.sql.Types.VARCHAR       => VarcharType(precision)
            case _                            =>
                throw new SQLException("Unsupported type " + JDBCType.valueOf(sqlType).getName)
        }
    }

    /**
      * Quotes the identifier. This is used to put quotes around the identifier in case the column
      * name is a reserved keyword, or in case it contains characters that require quotes (e.g. space).
      */
    override def quoteIdentifier(colName: String): String = {
        s""""$colName""""
    }

    /**
      * Quotes a table name including the optional database prefix
      * @param table
      * @return
      */
    override def quote(table:TableIdentifier) : String = {
        if (table.database.isDefined)
            quoteIdentifier(table.database.get) + "." + quoteIdentifier(table.table)
        else
            quoteIdentifier(table.table)
    }

    /**
      * Escapes a String literal to be used in SQL statements
      * @param value
      * @return
      */
    override def escape(value: String): String = {
        if (value == null) null
        else StringUtils.replace(value, "'", "''")
    }

    /**
      * Creates an SQL literal from a given value
      * @param value
      * @return
      */
    override def literal(value:Any) : String = {
        value match {
            case s:String => "'" + escape(s) + "'"
            case ts:Date => s"date('${ts.toString}')"
            case ts:UtcTimestamp => s"timestamp(${ts.toEpochSeconds()})"
            case v:Any =>  v.toString
        }
    }

    /**
     * Returns true if the given table supports a specific table change
     * @param change
     * @return
     */
    override def supportsChange(table:TableIdentifier, change:TableChange) : Boolean = {
        change match {
            case _:DropColumn => true
            case a:AddColumn => a.column.nullable // Only allow nullable columns to be added
            case _:UpdateColumnNullability => true
            case _:UpdateColumnType => true
            case _:UpdateColumnComment => true
            case x:TableChange => throw new UnsupportedOperationException(s"Table change ${x} not supported")
        }
    }

    override def statement : SqlStatements = Statements

    override def expr : SqlExpressions = Expressions
}


class BaseStatements(dialect: SqlDialect) extends SqlStatements {
    /**
     * The SQL query that should be used to discover the schema of a table. It only needs to
     * ensure that the result set has the same schema as the table, such as by calling
     * "SELECT * ...". Dialects can override this method to return a query that works best in a
     * particular database.
     * @param table The name of the table.
     * @return The SQL query to use for discovering the schema.
     */
    override def schema(table: TableIdentifier): String = {
        s"SELECT * FROM ${dialect.quote(table)} WHERE 1=0"
    }

    override def createTable(table: TableDefinition): String = {
        val strSchema = table.fields.map { field =>
            val name = dialect.quoteIdentifier(field.name)
            val typ = dialect.getJdbcType(field.ftype).databaseTypeDefinition
            val nullable = if (field.nullable) ""
            else "NOT NULL"
            s"$name $typ $nullable"
        }.mkString(",\n")
        val createTableOptions = ""

        s"CREATE TABLE ${dialect.quote(table.identifier)} ($strSchema) $createTableOptions"
    }

    /**
     * Get the SQL query that should be used to find if the given table exists. Dialects can
     * override this method to return a query that works best in a particular database.
     * @param table  The name of the table.
     * @return The SQL query to use for checking the table.
     */
    override def tableExists(table: TableIdentifier) : String = {
        s"SELECT * FROM ${dialect.quote(table)} WHERE 1=0"
    }

    override def firstRow(table: TableIdentifier, condition:String) : String = {
        if (condition.isEmpty)
            s"SELECT * FROM ${dialect.quote(table)} LIMIT 1"
        else
            s"SELECT * FROM ${dialect.quote(table)} WHERE $condition LIMIT 1"
    }

    override def addColumn(table: TableIdentifier, columnName: String, dataType: String, isNullable: Boolean): String =
        s"ALTER TABLE ${dialect.quote(table)} ADD COLUMN ${dialect.quoteIdentifier(columnName)} $dataType"

    override def renameColumn(table: TableIdentifier, columnName: String, newName: String): String =
        s"ALTER TABLE ${dialect.quote(table)} RENAME COLUMN ${dialect.quoteIdentifier(columnName)} TO ${dialect.quoteIdentifier(newName)}"

    override def deleteColumn(table: TableIdentifier, columnName: String): String =
        s"ALTER TABLE ${dialect.quote(table)} DROP COLUMN ${dialect.quoteIdentifier(columnName)}"

    override def updateColumnType(table: TableIdentifier, columnName: String, newDataType: String): String =
        s"ALTER TABLE ${dialect.quote(table)} ALTER COLUMN ${dialect.quoteIdentifier(columnName)} $newDataType"

    override def updateColumnNullability(table: TableIdentifier, columnName: String, dataType:String, isNullable: Boolean): String = {
        val nullable = if (isNullable) "NULL" else "NOT NULL"
        s"ALTER TABLE ${dialect.quote(table)} ALTER COLUMN ${dialect.quoteIdentifier(columnName)} SET $nullable"
    }

    override def merge(table: TableIdentifier, targetAlias:String, targetSchema:Option[StructType], sourceAlias:String, sourceSchema:StructType, condition:Column, clauses:Seq[MergeClause]) : String = {
        val sourceColumns = sourceSchema.names
        val sqlPlaceholders = sourceColumns.map(_ => "?").mkString(",")
        val sourceAliases = MapIgnoreCase(sourceColumns.zipWithIndex.map { case(src,alias) => src -> s"C${alias+1}" })

        def getColumnExpressions(cols:Map[String,Column]) : Seq[(String,String)] = {
            if (cols.nonEmpty) {
                cols.toSeq.map { case(n,c) => n -> toSql(c.expr, sourceAlias, sourceAliases) }
            }
            else {
                targetSchema.get.names.flatMap(n => sourceAliases.get(n).map(c => n -> (sourceAlias + "." + dialect.quoteIdentifier(c))))
            }
        }

        val sqlMergeCondition = toSql(condition.expr, sourceAlias, sourceAliases)
        val sqlClauses = clauses.map {
            case i:InsertClause =>
                val cond = i.condition.map(c => " AND " + toSql(c.expr, sourceAlias, sourceAliases)).getOrElse("")
                val expressions = getColumnExpressions(i.columns)
                val columnNames = expressions.map { case(n,_) => dialect.quoteIdentifier(n) }
                val columnValues = expressions.map { case(_,e) => e }
                s"WHEN NOT MATCHED$cond THEN INSERT(${columnNames.mkString(",")}) VALUES(${columnValues.mkString(",")})"
            case u:UpdateClause =>
                val cond = u.condition.map(c => " AND " + toSql(c.expr, sourceAlias, sourceAliases)).getOrElse("")
                val expressions = getColumnExpressions(u.columns)
                val setters = expressions.map { case(n,c) => s"${dialect.quoteIdentifier(n)} = $c" }
                s"WHEN MATCHED$cond THEN UPDATE SET ${setters.mkString(", ")}"
            case d:DeleteClause =>
                val cond = d.condition.map(c => " AND " + toSql(c.expr, sourceAlias, sourceAliases)).getOrElse("")
                s"WHEN MATCHED$cond THEN DELETE"
        }
        s"""MERGE INTO ${dialect.quote(table)} $targetAlias
           |USING (VALUES($sqlPlaceholders)) $sourceAlias
           |ON $sqlMergeCondition
           |${sqlClauses.mkString("\n")}""".stripMargin
    }

    protected def toSql(expr:Expression, sourceAlias:String, sourceAliases:MapIgnoreCase[String]) : String = {
        val sourcePrefix = sourceAlias.toLowerCase(Locale.ROOT)
        val newExpr = expr.transformDown {
            case UnresolvedAttribute(x) if x.head.toLowerCase(Locale.ROOT) == sourcePrefix =>
                val srcAlias = sourceAliases(x.tail.head)
                val expr = x.head + "." + dialect.quoteIdentifier(srcAlias)
                UnresolvableExpression(expr)
            case UnresolvedAttribute(x) =>
                val expr = if (x.tail.nonEmpty) {
                        x.head + "." + dialect.quoteIdentifier(x.tail)
                    }
                    else {
                        dialect.quoteIdentifier(x.head)
                    }
                UnresolvableExpression(expr)
        }

        newExpr.sql
    }
}


class BaseExpressions(dialect: SqlDialect) extends SqlExpressions {
    override def in(column: String, values: Iterable[Any]): String = {
        dialect.quoteIdentifier(column) + " IN (" + values.map(dialect.literal).mkString(",") + ")"
    }

    override def eq(column: String, value: Any): String = {
        dialect.quoteIdentifier(column) + "=" + dialect.literal(value)
    }

    override def partition(partition: PartitionSpec): String = {
        def literal(value:Any) : String = {
            value match {
                case s:String => "'" + dialect.escape(s) + "'"
                case ts:Date => "'" + ts.toString + "'"
                case v:Any =>  v.toString
            }
        }        // Do not use column quoting for the PARTITION expression
        val partitionValues = partition.values.map { case (k, v) => k + "=" + literal(v) }
        s"PARTITION(${partitionValues.mkString(",")})"
    }
}
