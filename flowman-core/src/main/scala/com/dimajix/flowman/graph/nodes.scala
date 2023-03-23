/*
 * Copyright (C) 2021 The Flowman Authors
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

package com.dimajix.flowman.graph

import scala.collection.mutable

import com.dimajix.flowman.execution.Operation
import com.dimajix.flowman.execution.Phase
import com.dimajix.flowman.model.Mapping
import com.dimajix.flowman.model.MappingIdentifier
import com.dimajix.flowman.model.MappingOutputIdentifier
import com.dimajix.flowman.model.Relation
import com.dimajix.flowman.model.RelationIdentifier
import com.dimajix.flowman.model.ResourceIdentifier
import com.dimajix.flowman.model.Target
import com.dimajix.flowman.model.TargetIdentifier
import com.dimajix.flowman.types.Field


sealed abstract class Node {
    private[graph] val inEdges = mutable.Buffer[Edge]()
    private[graph] val outEdges = mutable.Buffer[Edge]()

    /** Unique node ID, generated by GraphBuilder */
    val id : Int

    def label : String = s"($id) ${category.lower}/$kind: '$name'"

    def category : Category
    def kind : String
    def name : String
    def fqName : String = name
    def project : Option[String]

    /**
     * List of incoming edges, i.e. the upstream nodes which provide input data
     * @return
     */
    def incoming : Seq[Edge] = inEdges

    /**
     * List of outgoing edges, i.e. downstream nodes which receive data from this node
     * @return
     */
    def outgoing : Seq[Edge] = outEdges

    /**
     * Returns upstream resources
     */
    def upstream : Seq[Edge] = incoming

    /**
     * Child nodes providing more detail. For example a "Mapping" node might contain detail information on individual
     * columns, which would be logical children of the mapping.
     * @return
     */
    def children : Seq[Node] = Seq.empty

    /**
     * Optional parent node. For example a "Column" node might be a child of a "Mapping" node
     * @return
     */
    def parent : Option[Node] = None

    /**
     * Create a nice string representation of the upstream dependency tree
     * @return
     */
    def upstreamDependencyTree : String = {
        label + "\n" + upstreamTreeRec
    }

    private def upstreamTreeRec : String = {
        def indentSubtree(lines:Iterator[String], margin:Boolean) : Iterator[String] = {
            if (lines.nonEmpty) {
                val prefix = if (margin) "  |  " else "     "
                val firstLine = "  +- " + lines.next()
                Iterator(firstLine) ++ lines.map(prefix + _)
            }
            else {
                Iterator()
            }
        }

        // Do not use incoming edges, but upstream edges instead - this mainly makes sense for MappingOutputs
        val trees = upstream.map { child =>
            child.label + "\n" + child.input.upstreamTreeRec
        }
        val headChildren = trees.dropRight(1)
        val lastChild = trees.takeRight(1)

        val headTree = headChildren.flatMap(l => indentSubtree(l.linesIterator, true))
        val tailTree = lastChild.flatMap(l => indentSubtree(l.linesIterator, false))
        (headTree ++ tailTree).mkString("\n")
    }
}

final class MappingRef(val id:Int, val mapping:Mapping, val outputs:Seq[MappingOutput]) extends Node {
    require(outputs.forall(_._parent == null))
    outputs.foreach(_._parent = this)

    override def category: Category = Category.MAPPING
    override def kind: String = mapping.kind
    override def name: String = mapping.name
    override def project: Option[String] = mapping.project.map(_.name)
    override def children: Seq[Node] = outputs
    def requires : Set[ResourceIdentifier] = mapping.requires
    def identifier : MappingIdentifier = mapping.identifier
}
final class TargetRef(val id:Int, val target:Target, val phase:Phase) extends Node {
    override def category: Category = Category.TARGET
    override def kind: String = target.kind
    override def name: String = target.name
    override def project: Option[String] = target.project.map(_.name)
    def provides : Set[ResourceIdentifier] = target.provides(phase)
    def requires : Set[ResourceIdentifier] = target.requires(phase)
    def identifier : TargetIdentifier = target.identifier
}
final class RelationRef(val id:Int, val relation:Relation, val fields:Seq[Column]=Seq.empty) extends Node {
    require(fields.forall(_._parent == null))
    fields.foreach(_._parent = this)

    override def category: Category = Category.RELATION
    override def kind: String = relation.kind
    override def name: String = relation.name
    override def project: Option[String] = relation.project.map(_.name)
    def provides : Set[ResourceIdentifier] = relation.provides(Operation.CREATE)
    def requires : Set[ResourceIdentifier] = relation.requires(Operation.CREATE)
    def identifier : RelationIdentifier = relation.identifier
}
final class MappingOutput(val id:Int, val output:String, val fields:Seq[Column]=Seq.empty) extends Node {
    require(fields.forall(_._parent == null))
    fields.foreach(_._parent = this)

    private[graph] var _parent: MappingRef = _

    override def toString: String = s"MappingOutput($id, ${_parent.id}, $output)"
    override def category: Category = Category.MAPPING_OUTPUT
    override def kind: String = "mapping_output"
    override def parent: Option[Node] = Some(_parent)
    override def name: String = output
    override def fqName: String = _parent.name + ":" + output
    override def project: Option[String] = _parent.project
    override def upstream : Seq[Edge] = _parent.incoming
    def mapping : MappingRef = _parent
    def identifier : MappingOutputIdentifier = MappingOutputIdentifier(_parent.identifier, output)
}
final class Column(val id:Int, val field:Field, val fields:Seq[Column]=Seq.empty) extends Node {
    require(fields.forall(_._parent == null))
    fields.foreach(_._parent = this)

    private[graph] var _parent: Node = _

    override def category: Category = Category.COLUMN
    override def kind: String = "column"
    override def parent: Option[Node] = Some(_parent)
    override def name: String = field.name
    override def fqName: String = _parent match {
        case output:MappingOutput => s"[${output.fqName}].${field.name}"
        case col:Column => s"${col.fqName}.${field.name}"
        case node:Node => s"${node.fqName}.${field.name}"
    }
    override def project: Option[String] = _parent.project
    override def children: Seq[Node] = fields
}
