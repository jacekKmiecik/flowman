package com.dimajix.dataflow.tools.dfexec.model

import com.dimajix.dataflow.execution.Context
import com.dimajix.dataflow.spec.Project

class CreateCommand extends AbstractCommand {
    def executeInternal(context:Context, dataflow:Project) : Boolean = {
        false
    }
}
