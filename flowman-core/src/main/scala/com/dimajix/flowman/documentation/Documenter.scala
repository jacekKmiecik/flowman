/*
 * Copyright 2022 Kaya Kupferschmidt
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

package com.dimajix.flowman.documentation

import com.dimajix.flowman.execution.Context
import com.dimajix.flowman.execution.Execution
import com.dimajix.flowman.execution.Phase
import com.dimajix.flowman.execution.Session
import com.dimajix.flowman.graph.Graph
import com.dimajix.flowman.model.Job
import com.dimajix.flowman.model.Project


case class Documenter(
    collectors:Seq[Collector],
    generators:Seq[Generator]
) {
    def execute(session:Session, job:Job, args:Map[String,Any]) : Unit = {
        val runner = session.runner
        runner.withExecution(isolated=true) { execution =>
            runner.withJobContext(job, args, Some(execution)) { (context, arguments) =>
                execute(context, execution, job.project.get)
            }
        }
    }
    private def execute(context:Context, execution: Execution, project:Project) : Unit = {
        // 1. Get Project documentation
        val projectDoc = ProjectDoc(
            project.name,
            project.version
        )

        // 2. Apply all other collectors
        val graph = Graph.ofProject(context, project, Phase.BUILD)
        val finalDoc = collectors.foldLeft(projectDoc)((doc, collector) => collector.collect(execution, graph, doc))

        // 3. Generate documentation
        generators.foreach { gen =>
            gen.generate(context, execution, finalDoc)
        }
    }
}