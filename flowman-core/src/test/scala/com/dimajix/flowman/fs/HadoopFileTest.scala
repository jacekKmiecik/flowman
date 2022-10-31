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

package com.dimajix.flowman.fs

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.dimajix.spark.testing.LocalTempDir


class HadoopFileTest extends AnyFlatSpec with Matchers with LocalTempDir {
    private val localFs = org.apache.hadoop.fs.FileSystem.getLocal(new Configuration())

    "The HadoopFile" should "work" in {
        val dir = HadoopFile(localFs, new fs.Path(tempDir.toURI))
        dir.path should be (new fs.Path(tempDir.toURI))
        dir.exists() should be (true)
        dir.isFile() should be (false)
        dir.isDirectory() should be (true)
        dir.isAbsolute() should be (true)

        (dir / dir.toString) should be (dir)

        val file = dir / "lala"
        file.exists() should be (false)
        file.isFile() should be (false)
        file.isDirectory() should be (false)
        file.isAbsolute() should be(true)
        file.parent should be (dir)
        file.name should be ("lala")
        file.withName("lolo") should be (dir / "lolo")
    }

    it should "work at root level" in {
        val dir = HadoopFile(localFs, new fs.Path("file:/"))
        dir.parent should be (dir)
        dir.path should be(new fs.Path("file:/"))
        dir.exists() should be(true)
        dir.isFile() should be(false)
        dir.isDirectory() should be(true)
        dir.isAbsolute() should be(true)

        val file = dir / "lala"
        file.exists() should be(false)
        file.isFile() should be(false)
        file.isDirectory() should be(false)
        file.isAbsolute() should be(true)
        file.parent should be(dir)
        file.name should be("lala")
        file.withName("lolo") should be(dir / "lolo")
    }

    it should "support creating entries" in {
        val tmp = HadoopFile(localFs, new fs.Path(tempDir.toURI))
        val file = tmp / ("lala-" + System.currentTimeMillis().toString + ".tmp")
        file.exists() should be(false)
        file.isFile() should be(false)
        file.isDirectory() should be(false)

        file.create().close()
        file.exists() should be(true)
        file.isFile() should be(true)
        file.isDirectory() should be(false)

        file.delete(false)
        file.exists() should be(false)
        file.isFile() should be(false)
        file.isDirectory() should be(false)
    }
}
