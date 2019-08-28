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

package com.dimajix.flowman.config

import java.util.NoSuchElementException


object FlowmanConf {
    private val configEntries = java.util.Collections.synchronizedMap(
        new java.util.HashMap[String, ConfigEntry[_]]())

    private def register(entry: ConfigEntry[_]): Unit = configEntries.synchronized {
        require(!configEntries.containsKey(entry.key),
            s"Duplicate FlowmanConf entry. ${entry.key} has been registered")
        configEntries.put(entry.key, entry)
    }

    def buildConf(key: String): ConfigBuilder = ConfigBuilder(key).onCreate(register)

    val SPARK_ENABLE_HIVE = buildConf("flowman.spark.enableHive")
        .doc("Enables Hive support. WHen using newer Hadoop versions, you might want to disable it")
        .booleanConf
        .createWithDefault(true)

}


class FlowmanConf(settings:Map[String,String]) {
    import FlowmanConf._

    settings.foreach{ case (key,value) => validateSetting(key, value) }

    private def validateSetting(key: String, value: String): Unit = {
        require(key != null, "key cannot be null")
        require(value != null, s"value cannot be null for key: $key")
        val entry = configEntries.get(key)
        if (entry != null) {
            // Only verify configs in the SQLConf object
            entry.valueConverter(value)
        }
    }

    def sparkEnableHive: Boolean = getConf(SPARK_ENABLE_HIVE)

    /** Return the value of Spark SQL configuration property for the given key. */
    @throws[NoSuchElementException]("if key is not set")
    def get(key: String): String = {
        settings.get(key).
            orElse {
                // Try to use the default value
                Option(configEntries.get(key)).map { e => e.defaultValueString }
            }.
            getOrElse(throw new NoSuchElementException(key))
    }

    /**
     * Return the `string` value of  configuration property for the given key. If the key is
     * not set yet, return `defaultValue`.
     */
    def get(key: String, defaultValue: String): String = {
        if (defaultValue != null && defaultValue != ConfigEntry.UNDEFINED) {
            val entry = configEntries.get(key)
            if (entry != null) {
                // Only verify configs in the SQLConf object
                entry.valueConverter(defaultValue)
            }
        }
        settings.getOrElse(key, defaultValue)
    }

    /**
     * Return the value of configuration property for the given key. If the key is not set
     * yet, return `defaultValue`. This is useful when `defaultValue` in ConfigEntry is not the
     * desired one.
     */
    def getConf[T](entry: ConfigEntry[T], defaultValue: T): T = {
        require(configEntries.get(entry.key) == entry, s"$entry is not registered")
        settings.get(entry.key).map(entry.valueConverter).getOrElse(defaultValue)
    }

    /**
     * Return the value of configuration property for the given key. If the key is not set
     * yet, return `defaultValue` in [[ConfigEntry]].
     */
    def getConf[T](entry: ConfigEntry[T]): T = {
        require(configEntries.get(entry.key) == entry, s"$entry is not registered")
        entry.evaluate(key => settings.get(key))
    }

    /**
     * Return the value of an optional configuration property for the given key. If the key
     * is not set yet, returns None.
     */
    def getConf[T](entry: OptionalConfigEntry[T]): Option[T] = {
        require(configEntries.get(entry.key) == entry, s"$entry is not registered")
        entry.evaluate(key => settings.get(key))
    }

    /**
     * Return whether a given key is set in this [[FlowmanConf]].
     */
    def contains(key: String): Boolean = {
        settings.contains(key)
    }
}
