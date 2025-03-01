/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.io.dns.internal

import java.net.InetSocketAddress

import org.apache.pekko
import pekko.PekkoException
import pekko.actor.{ Actor, ActorLogging, ActorRef, Stash }
import pekko.annotation.InternalApi
import pekko.io.Tcp
import pekko.io.dns.internal.DnsClient.Answer
import pekko.util.ByteString

/**
 * INTERNAL API
 */
@InternalApi private[pekko] class TcpDnsClient(tcp: ActorRef, ns: InetSocketAddress, answerRecipient: ActorRef)
    extends Actor
    with ActorLogging
    with Stash {
  import TcpDnsClient._

  override def receive: Receive = idle

  val idle: Receive = {
    case _: Message =>
      stash()
      log.debug("Connecting to [{}]", ns)
      tcp ! Tcp.Connect(ns)
      context.become(connecting)
  }

  val connecting: Receive = {
    case failure @ Tcp.CommandFailed(_: Tcp.Connect) =>
      throwFailure(s"Failed to connect to TCP DNS server at [$ns]", failure.cause)
    case _: Tcp.Connected =>
      log.debug("Connected to TCP address [{}]", ns)
      val connection = sender()
      context.become(ready(connection))
      connection ! Tcp.Register(self)
      unstashAll()
    case _: Message =>
      stash()
  }

  def ready(connection: ActorRef, buffer: ByteString = ByteString.empty): Receive = {
    case msg: Message =>
      val bytes = msg.write()
      connection ! Tcp.Write(encodeLength(bytes.length) ++ bytes)
    case failure @ Tcp.CommandFailed(_: Tcp.Write) =>
      throwFailure("Write failed", failure.cause)
    case Tcp.Received(newData) =>
      val data = buffer ++ newData
      // TCP DNS responses are prefixed by 2 bytes encoding the length of the response
      val prefixSize = 2
      if (data.length < prefixSize)
        context.become(ready(connection, data))
      else {
        val expectedPayloadLength = decodeLength(data)
        if (data.drop(prefixSize).length < expectedPayloadLength)
          context.become(ready(connection, data))
        else {
          answerRecipient ! parseResponse(data.drop(prefixSize))
          context.become(ready(connection, ByteString.empty))
          if (data.length > prefixSize + expectedPayloadLength) {
            self ! Tcp.Received(data.drop(prefixSize + expectedPayloadLength))
          }
        }
      }
    case Tcp.PeerClosed =>
      context.become(idle)
  }

  private def parseResponse(data: ByteString) = {
    val msg = Message.parse(data)
    log.debug("Decoded TCP DNS response [{}]", msg)
    if (msg.flags.isTruncated) {
      log.warning("TCP DNS response truncated")
    }
    val (recs, additionalRecs) =
      if (msg.flags.responseCode == ResponseCode.SUCCESS) (msg.answerRecs, msg.additionalRecs) else (Nil, Nil)
    Answer(msg.id, recs, additionalRecs)
  }
}
private[internal] object TcpDnsClient {
  def encodeLength(length: Int): ByteString =
    ByteString((length / 256).toByte, length.toByte)

  def decodeLength(data: ByteString): Int =
    ((data(0).toInt + 256) % 256) * 256 + ((data(1) + 256) % 256)

  def throwFailure(message: String, cause: Option[Throwable]): Unit =
    cause match {
      case None =>
        throw new PekkoException(message)
      case Some(throwable) =>
        throw new PekkoException(message, throwable)
    }
}
