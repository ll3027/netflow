/**
 * Copyright 2015 ICT.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ac.ict.acs.netflow.util

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.Await

import akka.actor.{ActorRef, ExtendedActorSystem, ActorSystem}
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Level, Logger}

import cn.ac.ict.acs.netflow.{NetFlowException, NetFlowConf, Logging}

/**
 * Various utility classes for working with Akka.
 *
 * Copied from org.apache.spark.util.AkkaUtils
 */
private[netflow] object AkkaUtils extends Logging {

  /**
   * Creates an ActorSystem ready for remoting. Returns both the
   * ActorSystem itself and its port (which is hard to get from Akka).
   *
   * Note: the `name` parameter is important, as even if a client sends a message to right
   * host + port, if the system name is incorrect, Akka will drop the message.
   *
   */
  def createActorSystem(
      name: String,
      host: String,
      port: Int,
      conf: NetFlowConf): (ActorSystem, Int) = {
    val startService: Int => (ActorSystem, Int) = { actualPort =>
      doCreateActorSystem(name, host, actualPort, conf)
    }
    Utils.startServiceOnPort(port, startService, conf, name)
  }

  private def doCreateActorSystem(
      name: String,
      host: String,
      port: Int,
      conf: NetFlowConf): (ActorSystem, Int) = {

    val akkaThreads = conf.getInt("netflow.akka.threads", 4)
    val akkaBatchSize = conf.getInt("netflow.akka.batchSize", 15)
    val akkaTimeout = conf.getInt("netflow.akka.timeout",
      conf.getInt("netflow.network.timeout", 120))
    val akkaFrameSize = maxFrameSizeBytes(conf)
    val akkaLogLifecycleEvents = conf.getBoolean("netflow.akka.logLifecycleEvents", false)
    val lifecycleEvents = if (akkaLogLifecycleEvents) "on" else "off"
    if (!akkaLogLifecycleEvents) {
      // As a workaround for Akka issue #3787, we coerce the "EndpointWriter" log to be silent.
      // See: https://www.assembla.com/spaces/akka/tickets/3787#/
      Option(Logger.getLogger("akka.remote.EndpointWriter")).map(l => l.setLevel(Level.FATAL))
    }

    val logAkkaConfig = if (conf.getBoolean("netflow.akka.logAkkaConfig", false)) "on" else "off"

    val akkaHeartBeatPauses = conf.getInt("netflow.akka.heartbeat.pauses", 6000)
    val akkaHeartBeatInterval = conf.getInt("netflow.akka.heartbeat.interval", 1000)


    val akkaConf = ConfigFactory.parseString(
      s"""
         |akka.daemonic = on
         |akka.loggers = [""akka.event.slf4j.Slf4jLogger""]
         |akka.stdout-loglevel = "ERROR"
         |akka.jvm-exit-on-fatal-error = off
         |akka.remote.transport-failure-detector.heartbeat-interval = $akkaHeartBeatInterval s
         |akka.remote.transport-failure-detector.acceptable-heartbeat-pause = $akkaHeartBeatPauses s
         |akka.actor.provider = "akka.remote.RemoteActorRefProvider"
         |akka.remote.netty.tcp.transport-class = "akka.remote.transport.netty.NettyTransport"
         |akka.remote.netty.tcp.hostname = "$host"
         |akka.remote.netty.tcp.port = $port
         |akka.remote.netty.tcp.tcp-nodelay = on
         |akka.remote.netty.tcp.connection-timeout = $akkaTimeout s
         |akka.remote.netty.tcp.maximum-frame-size = ${akkaFrameSize}B
         |akka.remote.netty.tcp.execution-pool-size = $akkaThreads
         |akka.actor.default-dispatcher.throughput = $akkaBatchSize
         |akka.log-config-on-start = $logAkkaConfig
         |akka.remote.log-remote-lifecycle-events = $lifecycleEvents
         |akka.log-dead-letters = $lifecycleEvents
         |akka.log-dead-letters-during-shutdown = $lifecycleEvents
      """.stripMargin)

    val actorSystem = ActorSystem(name, akkaConf)
    val provider = actorSystem.asInstanceOf[ExtendedActorSystem].provider
    val boundPort = provider.getDefaultAddress.port.get
    (actorSystem, boundPort)
  }

  /** Returns the default NetFlow timeout to use for Akka ask operations. */
  def askTimeout(conf: NetFlowConf): FiniteDuration = {
    Duration.create(conf.getLong("netflow.akka.askTimeout", 30), "seconds")
  }

  /** Returns the default NetFlow timeout to use for Akka remote actor lookup. */
  def lookupTimeout(conf: NetFlowConf): FiniteDuration = {
    Duration.create(conf.getLong("netflow.akka.lookupTimeout", 30), "seconds")
  }

  private val AKKA_MAX_FRAME_SIZE_IN_MB = Int.MaxValue / 1024 / 1024

  /** Returns the configured max frame size for Akka messages in bytes. */
  def maxFrameSizeBytes(conf: NetFlowConf): Int = {
    val frameSizeInMB = conf.getInt("netflow.akka.frameSize", 10)
    if (frameSizeInMB > AKKA_MAX_FRAME_SIZE_IN_MB) {
      throw new IllegalArgumentException(
        s"netflow.akka.frameSize should not be greater than $AKKA_MAX_FRAME_SIZE_IN_MB MB")
    }
    frameSizeInMB * 1024 * 1024
  }

  /** Space reserved for extra data in an Akka message besides serialized task or task result. */
  val reservedSizeBytes = 200 * 1024

  /** Returns the configured number of times to retry connecting */
  def numRetries(conf: NetFlowConf): Int = {
    conf.getInt("netflow.akka.num.retries", 3)
  }

  /** Returns the configured number of milliseconds to wait on each retry */
  def retryWaitMs(conf: NetFlowConf): Int = {
    conf.getInt("netflow.akka.retry.wait", 3000)
  }

  /**
   * Send a message to the given actor and get its result within a default timeout, or
   * throw a NetFlowException if this fails.
   */
  def askWithReply[T](
      message: Any,
      actor: ActorRef,
      timeout: FiniteDuration): T = {
    askWithReply[T](message, actor, maxAttempts = 1, retryInterval = Int.MaxValue, timeout)
  }

  /**
   * Send a message to the given actor and get its result within a default timeout, or
   * throw a NetFlowException if this fails even after the specified number of retries.
   */
  def askWithReply[T](
      message: Any,
      actor: ActorRef,
      maxAttempts: Int,
      retryInterval: Int,
      timeout: FiniteDuration): T = {
    // TODO: Consider removing multiple attempts
    if (actor == null) {
      throw new NetFlowException(s"Error sending message [message = $message]" +
        " as actor is null ")
    }
    var attempts = 0
    var lastException: Exception = null
    while (attempts < maxAttempts) {
      attempts += 1
      try {
        val future = actor.ask(message)(timeout)
        val result = Await.result(future, timeout)
        if (result == null) {
          throw new NetFlowException("Actor returned null")
        }
        return result.asInstanceOf[T]
      } catch {
        case ie: InterruptedException => throw ie
        case e: Exception =>
          lastException = e
          logWarning(s"Error sending message [message = $message] in $attempts attempts", e)
      }
      Thread.sleep(retryInterval)
    }

    throw new NetFlowException(
      s"Error sending message [message = $message]", lastException)
  }

//  def makeDriverRef(name: String, conf: SparkConf, actorSystem: ActorSystem): ActorRef = {
//    val driverActorSystemName = SparkEnv.driverActorSystemName
//    val driverHost: String = conf.get("spark.driver.host", "localhost")
//    val driverPort: Int = conf.getInt("spark.driver.port", 7077)
//    Utils.checkHost(driverHost, "Expected hostname")
//    val url = address(protocol(actorSystem), driverActorSystemName, driverHost, driverPort, name)
//    val timeout = AkkaUtils.lookupTimeout(conf)
//    logInfo(s"Connecting to $name: $url")
//    Await.result(actorSystem.actorSelection(url).resolveOne(timeout), timeout)
//  }
//
//  def makeExecutorRef(
//      name: String,
//      conf: SparkConf,
//      host: String,
//      port: Int,
//      actorSystem: ActorSystem): ActorRef = {
//    val executorActorSystemName = SparkEnv.executorActorSystemName
//    Utils.checkHost(host, "Expected hostname")
//    val url = address(protocol(actorSystem), executorActorSystemName, host, port, name)
//    val timeout = AkkaUtils.lookupTimeout(conf)
//    logInfo(s"Connecting to $name: $url")
//    Await.result(actorSystem.actorSelection(url).resolveOne(timeout), timeout)
//  }

  def protocol(actorSystem: ActorSystem): String = {
    val akkaConf = actorSystem.settings.config
    val sslProp = "akka.remote.netty.tcp.enable-ssl"
    protocol(akkaConf.hasPath(sslProp) && akkaConf.getBoolean(sslProp))
  }

  def protocol(ssl: Boolean = false): String = {
    if (ssl) {
      "akka.ssl.tcp"
    } else {
      "akka.tcp"
    }
  }

  def address(
      protocol: String,
      systemName: String,
      host: String,
      port: Any,
      actorName: String): String = {
    s"$protocol://$systemName@$host:$port/user/$actorName"
  }

}
