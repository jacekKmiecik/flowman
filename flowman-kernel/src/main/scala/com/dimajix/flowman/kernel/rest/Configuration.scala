/*
 * Copyright 2019 Kaya Kupferschmidt
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

package com.dimajix.flowman.kernel.rest

import java.io.FileInputStream

import java.util.Properties


object Configuration {
    val SERVER_BIND_HOST = "kernel.server.bind.host"
    val SERVER_BIND_PORT = "kernel.server.bind.port"
    val SERVER_BIND_METRICS_PORT = "kernel.server.bind.metrics-port"
    val SERVER_REQUEST_TIMEOUT = "kernel.server.request.timeout"
    val SERVER_IDLE_TIMEOUT = "kernel.server.idle.timeout"
    val SERVER_BIND_TIMEOUT = "kernel.server.bind.timeout"
    val SERVER_LINGER_TIMEOUT = "kernel.server.linger.timeout"

    private def defaultProperties() : Properties = {
        val loader = Thread.currentThread.getContextClassLoader
        val url = loader.getResource("com/dimajix/flowman/flowman-server.properties")
        val properties = new Properties()
        properties.load(url.openStream())
        properties
    }

    /**
     * Load a Configuration from a Properties file
     * @param filename
     * @return
     */
    def load(filename:String) : Configuration= {
        val properties = defaultProperties()
        properties.load(new FileInputStream(filename))
        new Configuration(properties)
    }

    /**
     * Loads built-in default configuration
     * @return
     */
    def loadDefaults() : Configuration = {
        val properties = defaultProperties()
        new Configuration(properties)
    }
}

class Configuration(properties: Properties) {
    import Configuration._

    def getBindHost() : String = properties.getProperty(SERVER_BIND_HOST, "0.0.0.0")
    def getBindPort() : Int = properties.getProperty(SERVER_BIND_PORT, "8080").toInt
    def getBindMetricsPort() : Int = properties.getProperty(SERVER_BIND_METRICS_PORT, "8081").toInt

    def getRequestTimeout() : Int = properties.getProperty(SERVER_REQUEST_TIMEOUT, "20").toInt
    def getIdleTimeout() : Int = properties.getProperty(SERVER_IDLE_TIMEOUT, "60").toInt
    def getBindTimeout() : Int = properties.getProperty(SERVER_BIND_TIMEOUT, "1").toInt
    def getLingerTimeout() : Int = properties.getProperty(SERVER_LINGER_TIMEOUT, "60").toInt
}
