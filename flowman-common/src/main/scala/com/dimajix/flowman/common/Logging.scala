/*
 * Copyright 2020-2022 Kaya Kupferschmidt
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

package com.dimajix.flowman.common

import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale
import java.util.Properties

import scala.collection.JavaConverters._

import org.apache.log4j.PropertyConfigurator
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationBuilder
import org.slf4j.LoggerFactory
import org.slf4j.impl.StaticLoggerBinder


class Logging
object Logging {
    private lazy val logger = LoggerFactory.getLogger(classOf[Logging])
    private var log4j1Properties:Properties = null
    private var level:String = null
    private var color:Boolean = true

    def init() : Unit = {
        val log4jConfig = System.getProperty("log4j.configuration")
        if (isCustomLog4j2()) {
            // Do nothing, config is already correctly loaded
        }
        else if (log4jConfig != null) {
            val url = try {
                    new URL(log4jConfig)
                }
                catch {
                    case _: MalformedURLException => new File(log4jConfig).toURI.toURL
                }
            initlog4j1(url)
        }
        else {
            initDefault()
        }
    }

    def setColorEnabled(enabled:Boolean) : Unit = {
        color = enabled
        if (isLog4j2() && !isCustomLog4j2()) {
            initDefault()
        }
    }

    private def initDefault() : Unit = {
        val loader = Thread.currentThread.getContextClassLoader
        if (isLog4j2()) {
            val url = loader.getResource("com/dimajix/flowman/log4j2-defaults.properties")
            LoggingImplLog4j2.init(url, color)
        }
        else {
            val url = loader.getResource("com/dimajix/flowman/log4j-defaults.properties")
            initlog4j1(url)
        }
    }

    private def initlog4j1(configUrl:URL) : Unit = {
        log4j1Properties = loadProperties(configUrl)
        reconfigureLogging()
        logger.debug(s"Loaded logging configuration from $configUrl")
    }

    private[common] def loadProperties(url:URL) : Properties = {
        try {
            val urlConnection = url.openConnection
            urlConnection.setUseCaches(false)
            val inputStream = urlConnection.getInputStream
            try {
                val loaded = new Properties
                loaded.load(inputStream)
                loaded
            } finally {
                inputStream.close()
            }
        } catch {
            case e: IOException =>
                logger.warn("Could not read configuration file from URL [" + url + "].", e)
                logger.warn("Ignoring configuration file [" + url + "].")
                null
        }
    }

    def setSparkLogging(logLevel:String) : Unit = {
        // Adjust Spark logging level
        logger.debug(s"Setting Spark log level to ${logLevel}")

        val upperCased = logLevel.toUpperCase(Locale.ENGLISH)
        val l = org.apache.log4j.Level.toLevel(upperCased)
        org.apache.log4j.Logger.getLogger("org").setLevel(l)
        org.apache.log4j.Logger.getLogger("akka").setLevel(l)
        org.apache.log4j.Logger.getLogger("hive").setLevel(l)
    }

    def setLogging(logLevel:String) : Unit = {
        logger.debug(s"Setting global log level to ${logLevel}")
        level = logLevel.toUpperCase(Locale.ENGLISH)

        reconfigureLogging()
    }

    private def reconfigureLogging() : Unit = {
        // Create new logging properties with all levels being replaced
        if (log4j1Properties != null) {
            LoggingImplLog4j1.reconfigure(log4j1Properties, level)
        }
        else if (isLog4j2()) {
            if (level != null) {
                LoggingImplLog4j2.reconfigure(level)
            }
        }

        if (level != null) {
            val l = org.apache.log4j.Level.toLevel(level)
            org.apache.log4j.Logger.getRootLogger().setLevel(l)
        }
    }

    private def isLog4j2(): Boolean = {
        // This distinguishes the log4j 1.2 binding, currently
        // org.slf4j.impl.Log4jLoggerFactory, from the log4j 2.0 binding, currently
        // org.apache.logging.slf4j.Log4jLoggerFactory
        val binderClass = StaticLoggerBinder.getSingleton.getLoggerFactoryClassStr
        "org.apache.logging.slf4j.Log4jLoggerFactory".equals(binderClass)
    }

    private def isCustomLog4j2(): Boolean = {
        val log4jConfigFile = System.getProperty("log4j.configurationFile")
        val log4j2ConfigFile = System.getProperty("log4j2.configurationFile")
        isLog4j2() && (log4jConfigFile != null || log4j2ConfigFile != null)
    }
}


object LoggingImplLog4j1 {
    def reconfigure(props:Properties, level: String): Unit = {
        val newProps = new Properties()
        props.asScala.foreach { case (k, v) => newProps.setProperty(k, v) }
        if (level != null) {
            newProps.keys().asScala.collect { case s: String => s }
                .toList
                .filter(k => k.startsWith("log4j.logger."))
                .foreach(k => newProps.setProperty(k, level))
        }
        PropertyConfigurator.configure(newProps)
    }
}


/**
 * Utility class for isolating log4j 2.x dependencies. Otherwise a ClassNotFoundException may be thrown if log4j 2.x
 * is not on the classpath, even if the code is not executed.
 */
object LoggingImplLog4j2 {
    def init(configUrl: URL, color:Boolean): Unit = {
        val props = Logging.loadProperties(configUrl)
        // Disable ANSI magic by adding new entries to the log4j config
        if (!color) {
            props.keys().asScala.collect { case s: String => s }
                .foreach { k =>
                    val parts = k.split('.')
                    if (parts.length >= 3 && parts(0) == "appender" && parts(2) == "layout")
                        props.setProperty("appender." + parts(1) + ".layout.disableAnsi", "true")
                }
        }
        val config = new PropertiesConfigurationBuilder()
            .setRootProperties(props)
            .build()
        val context = LogManager.getContext(false).asInstanceOf[LoggerContext]
        context.start(config)
    }

    def reconfigure(level:String) : Unit = {
        val context = LogManager.getContext(false).asInstanceOf[LoggerContext]
        val l = Level.getLevel(level)
        val config = context.getConfiguration
        config.getRootLogger.setLevel(l)
        config.getLoggers.asScala.values.foreach(_.setLevel(l))
        context.updateLoggers()
    }
}