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

package com.dimajix.flowman.types

import scala.collection.JavaConversions._

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.swagger.models.ComposedModel
import io.swagger.models.Model
import io.swagger.models.ModelImpl
import io.swagger.models.Swagger
import io.swagger.models.properties.ArrayProperty
import io.swagger.models.properties.BaseIntegerProperty
import io.swagger.models.properties.BinaryProperty
import io.swagger.models.properties.BooleanProperty
import io.swagger.models.properties.ByteArrayProperty
import io.swagger.models.properties.DateProperty
import io.swagger.models.properties.DateTimeProperty
import io.swagger.models.properties.DecimalProperty
import io.swagger.models.properties.DoubleProperty
import io.swagger.models.properties.FloatProperty
import io.swagger.models.properties.IntegerProperty
import io.swagger.models.properties.LongProperty
import io.swagger.models.properties.MapProperty
import io.swagger.models.properties.ObjectProperty
import io.swagger.models.properties.Property
import io.swagger.models.properties.StringProperty
import io.swagger.models.properties.UUIDProperty
import io.swagger.parser.util.DeserializationUtils
import io.swagger.parser.util.SwaggerDeserializer
import io.swagger.util.Json


object SwaggerSchemaUtils {
    /**
      * Convert an entity of a Swagger schema into a Flowman schema. Optionally mark all fields as optional.
      *
      * @param schema
      * @param entity
      * @param nullable
      * @return
      */
    def fromSwagger(schema:String, entity:Option[String]=None, nullable:Boolean=true) : Seq[Field] = {
        val swagger = parseSwagger(schema)
        fromSwagger(swagger, entity, nullable)
    }

    /**
      * Convert an entity of a Swagger schema into a Flowman schema. Optionally mark all fields as optional.
      *
      * @param swagger
      * @param entity
      * @param nullable
      * @return
      */
    def fromSwagger(swagger:Swagger, entity:Option[String], nullable:Boolean) : Seq[Field] = {
        val model = entity.filter(_.nonEmpty).map(e => swagger.getDefinitions()(e)).getOrElse(swagger.getDefinitions().values().head)
        fromSwagger(model, nullable)
    }

    /**
      * Convert an entity of a Swagger schema into a Flowman schema. Optionally mark all fields as optional.
      *
      * @param model
      * @param nullable
      * @return
      */
    def fromSwagger(model:Model, nullable:Boolean) : Seq[Field] = {
        def fromSwaggerRec(model:Model, nullable:Boolean=true) : Seq[Field] = {
            model match {
                case composed: ComposedModel => composed.getAllOf.flatMap(m => fromSwaggerRec(m, nullable))
                //case array:ArrayModel => Seq(fromSwaggerProperty(array.getItems))
                case _ => fromSwaggerObject(model.getProperties.toSeq, "", nullable).fields
            }
        }

        if (!model.isInstanceOf[ModelImpl] && !model.isInstanceOf[ComposedModel])
            throw new IllegalArgumentException("Root type in Swagger must be a simple model or composed model")

        fromSwaggerRec(model, nullable)
    }

    /**
      * Parse a string as a Swagger schema. This will also fix some incompatible representations (nested allOf)
      *
      * @param data
      * @return
      */
    def parseSwagger(data: String): Swagger = {
        val rootNode = if (data.trim.startsWith("{")) {
            val mapper = Json.mapper
            mapper.readTree(data)
        }
        else {
            DeserializationUtils.readYamlTree(data)
        }

        // Fix nested "allOf" nodes, which have to be in "definitions->[Entity]->[Definition]"
        val definitions = rootNode.path("definitions")
        val entities = definitions.elements().flatMap(_.elements()).toList
        entities.foreach(replaceAllOf)
        entities.foreach(fixRequired)

        val result = new SwaggerDeserializer().deserialize(rootNode)
        val convertValue = result.getSwagger
        convertValue
    }

    /**
      * This helper method transforms the Json tree such that "allOf" inline elements will be replaced by an
      * adequate object definition, because Swagger will not parse inline allOf elements correctly
      * @param jsonNode
      */
    private def replaceAllOf(jsonNode: JsonNode) : Unit = {
        jsonNode match {
            case obj:ObjectNode =>
                if (obj.get("allOf") != null) {
                    val children = obj.get("allOf").elements().toSeq
                    val required = children.flatMap(c => Option(c.get("required")).toSeq.flatMap(_.elements()))
                    val properties = children.flatMap(c => Option(c.get("properties")).toSeq.flatMap(_.fields()))
                    val desc = children.flatMap(c => Option(c.get("description"))).headOption
                    obj.without("allOf")
                    obj.set("type", TextNode.valueOf("object"))
                    obj.withArray("required").addAll(required)
                    properties.foreach(x => obj.`with`("properties").set(x.getKey, x.getValue))
                    desc.foreach(d => obj.set("description", d))
                }
            case _:JsonNode =>
        }
        jsonNode.elements().foreach(replaceAllOf)
    }

    private def fixRequired(jsonNode: JsonNode) : Unit = {
        jsonNode match {
            case obj:ObjectNode =>
                if (obj.has("required") && obj.get("required").isNull()) {
                    obj.without("required")
                }
            case _:JsonNode =>
        }
        jsonNode.elements().foreach(fixRequired)
    }

    private def fromSwaggerObject(properties:Seq[(String,Property)], prefix:String, nullable:Boolean) : StructType = {
        StructType(properties.map(np => fromSwaggerProperty(np._1, np._2, prefix, nullable)))
    }

    private def fromSwaggerProperty(name:String, property:Property, prefix:String, nullable:Boolean) : Field = {
        Field(
            name,
            fromSwaggerType(property, prefix + name, nullable),
            nullable || !property.getRequired,
            Option(property.getDescription),
            None, // default value
            None, // size
            Option(property.getFormat)
        )
    }

    private def fromSwaggerType(property:Property, fqName:String, nullable:Boolean) : FieldType = {
        property match {
            case array:ArrayProperty => ArrayType(fromSwaggerType(array.getItems, fqName + ".items", nullable))
            case _:BinaryProperty => BinaryType
            case _:BooleanProperty => BooleanType
            case _:ByteArrayProperty => BinaryType
            case _:DateProperty => DateType
            case _:DateTimeProperty => TimestampType
            case _:FloatProperty => FloatType
            case _:DoubleProperty => DoubleType
            case d:DecimalProperty =>
                val scale = if (d.getMultipleOf != null) d.getMultipleOf.scale() else DecimalType.USER_DEFAULT.scale
                val precision = if (d.getMaximum != null) d.getMaximum.precision() else DecimalType.USER_DEFAULT.precision - scale
                DecimalType(precision + scale, scale)
            case _:IntegerProperty => IntegerType
            case _:LongProperty => LongType
            case _:BaseIntegerProperty => IntegerType
            case _:MapProperty => MapType(StringType, StringType)
            case obj:ObjectProperty => fromSwaggerObject(obj.getProperties.toSeq, fqName + ".", nullable)
            case s:StringProperty =>
                val minLength = Option(s.getMinLength).map(_.intValue())
                val maxLength = Option(s.getMaxLength).map(_.intValue())
                (minLength, maxLength) match {
                    case (Some(l1),Some(l2)) if l1==l2 => CharType(l1)
                    case (_,Some(l)) => VarcharType(l)
                    case (_,_) => StringType
                }
            case _:UUIDProperty => StringType
            case _ => throw new UnsupportedOperationException(s"Swagger type $property of field $fqName not supported")
        }
    }
}