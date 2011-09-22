/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can

import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.channels.{SocketChannel, SelectionKey}
import utils.LinkedList
import java.nio.ByteBuffer
import collection.mutable.Queue
import java.lang.IllegalStateException
import akka.dispatch.{DefaultCompletableFuture, CompletableFuture, Future}
import akka.actor.{ActorRef, Actor, UntypedChannel}

sealed trait HttpConnection {
  def send(request: HttpRequest): Future[HttpResponse]
  def send(request: HttpRequest, receiver: ActorRef, context: Option[Any] = None)
  def startRequestStream(requestStart: ChunkedRequestStart): StreamHandler
  def close()
}

// public incoming message
case class Connect(host: String, port: Int = 80)

object HttpClient extends HighLevelHttpClient {
  private[can] case class Send(conn: ClientConnection, request: HttpRequest, buffers: List[ByteBuffer],
                               responder: AnyRef => Unit)
  private case class Close(conn: ClientConnection)
  private case class RequestRecord(request: HttpRequest, conn: ClientConnection)
          extends LinkedList.Element[RequestRecord]
  private[can] abstract class PendingResponse(val request: HttpRequest) {
    def deliver(response: AnyRef)
  }
  private[can] class ClientConnection(key: SelectionKey, val host: String, val port: Int,
                                      val connectionResponseChannel: Option[UntypedChannel] = None)
          extends Connection[ClientConnection](key) {
    val pendingResponses = Queue.empty[PendingResponse]
    val requestQueue = Queue.empty[Send]
    key.attach(this)
    messageParser = UnexpectedResponseErrorParser
    def closeAllPendingWithError(error: String) { pendingResponses.foreach(_.deliver(new HttpClientException(error))) }
    def resetParser(config: MessageParserConfig) {
      messageParser = {
        if (pendingResponses.isEmpty) UnexpectedResponseErrorParser
        else new EmptyResponseParser(config, pendingResponses.head.request.method)
      }
    }
  }
  private val UnexpectedResponseErrorParser = ErrorParser("Received unexpected HttpResponse")
}

class HttpClient(val config: ClientConfig = ClientConfig.fromAkkaConf) extends HttpPeer {
  import HttpClient._

  private lazy val log = LoggerFactory.getLogger(getClass)
  private val openRequests = new LinkedList[RequestRecord]

  private[can] type Conn = ClientConnection

  self.id = config.clientActorId

  // we use our own custom single-thread dispatcher, because our thread will, for the most time,
  // be blocked at selector selection, therefore we need to wake it up upon message or task arrival
  self.dispatcher = new SelectorWakingDispatcher("spray-can-client", selector)

  override def preStart() {
    log.info("Starting spray-can HTTP client")
    super.preStart()
  }

  override def postStop() {
    log.info("Stopped spray-can HTTP client")
    super.postStop()
  }

  protected override def receive = super.receive orElse {
    case Connect(host, port) => initiateConnection(host, port)
    case send: Send => {
      if (send.conn.writeBuffers.isEmpty)
        prepareWriting(send)
      else
        send.conn.requestQueue.enqueue(send)
    }
    case Close(conn) => close(conn)
  }

  private def initiateConnection(host: String, port: Int) {
    val address = new InetSocketAddress(host, port)
    log.debug("Initiating new connection to {}", address)
    protectIO("Init connect") {
      val socketChannel = SocketChannel.open()
      socketChannel.configureBlocking(false)
      if (socketChannel.connect(address)) {
        log.debug("New connection immediately established")
        val key = socketChannel.register(selector, SelectionKey.OP_READ) // start out with writing disabled
        val conn = new ClientConnection(key, host, port)
        connections += conn
        new HttpConnectionImpl(conn)
      } else {
        log.debug("Connection request registered")
        val key = socketChannel.register(selector, SelectionKey.OP_CONNECT)
        new ClientConnection(key, host, port, Some(self.channel))
      }
    } match {
      case Right(_: ClientConnection) => // nothing to do
      case Right(x: HttpConnection) => if (!self.tryReply(x)) log.error("Couldn't reply to Connect message")
      case Left(error) => self.channel.sendException {
        new HttpClientException("Could not connect to " + address + ": " + error)
      }
      case _ => throw new IllegalStateException
    }
  }

  protected def handleConnectionEvent(key: SelectionKey) {
    if (key.isConnectable) {
      val conn = key.attachment.asInstanceOf[ClientConnection]
      conn.connectionResponseChannel.foreach { channel =>
        protectIO("Connect", conn) {
          key.channel.asInstanceOf[SocketChannel].finishConnect()
          conn.key.interestOps(SelectionKey.OP_READ)
          connections += conn
          log.debug("Connected to {}:{}", conn.host, conn.port)
          new HttpConnectionImpl(conn)
        } match {
          case Right(x) =>  if (!channel.tryTell(x)) log.error("Couldn't reply to Connect message")
          case Left(error) => channel.sendException {
            new HttpClientException("Could not connect to " + conn.host + ':' + conn.port + " due to " + error)
          }
        }
      }
    } else throw new IllegalStateException
  }

  private def prepareWriting(send: Send) {
    import send._
    if (conn.key.isValid) {
      log.debug("Received raw request, scheduling write")
      val requestRecord = RequestRecord(request, conn)
      conn.pendingResponses.enqueue(new PendingResponse(request) {
        def deliver(response: AnyRef) {
          log.debug("Delivering received response: {}", response)
          send.responder(response)
          openRequests -= requestRecord
        }
      })
      conn.writeBuffers = buffers
      conn.enableWriting()
      openRequests += requestRecord
      requestsDispatched += 1
    } else {
      send.responder(new HttpClientException("Cannot send request due to closed connection"))
    }
  }

  protected def finishWrite(conn: ClientConnection) {
    import conn._
    if (writeBuffers.isEmpty) {
      if (messageParser == UnexpectedResponseErrorParser)
        messageParser = new EmptyResponseParser(config.parserConfig, pendingResponses.head.request.method)
      if (requestQueue.isEmpty) {
        disableWriting()
      } else {
        prepareWriting(requestQueue.dequeue())
      }
    }
  }

  protected def handleCompleteMessage(conn: Conn, parser: CompleteMessageParser) {
    import parser._
    val statusLine = messageLine.asInstanceOf[StatusLine]
    import statusLine._
    val response = HttpResponse(status, headers, body, protocol)
    assert(!conn.pendingResponses.isEmpty)
    conn.pendingResponses.dequeue().deliver(response)
    conn.resetParser(config.parserConfig)
  }

  protected def handleChunkedStart(conn: Conn, parser: ChunkedStartParser) {
    val statusLine = parser.messageLine.asInstanceOf[StatusLine]
    assert(!conn.pendingResponses.isEmpty)
    conn.pendingResponses.head.deliver(ChunkedResponseStart(statusLine.status, parser.headers))
    conn.messageParser = new ChunkParser(config.parserConfig)
  }

  protected def handleChunkedChunk(conn: Conn, parser: ChunkedChunkParser) {
    assert(!conn.pendingResponses.isEmpty)
    conn.pendingResponses.head.deliver(MessageChunk(parser.extensions, parser.body))
    conn.messageParser = new ChunkParser(config.parserConfig)
  }

  protected def handleChunkedEnd(conn: Conn, parser: ChunkedEndParser) {
    assert(!conn.pendingResponses.isEmpty)
    conn.pendingResponses.dequeue().deliver(ChunkedResponseEnd(parser.extensions, parser.trailer))
    conn.resetParser(config.parserConfig)
  }

  protected def handleParseError(conn: Conn, parser: ErrorParser) {
    log.warn("Received illegal response: {}", parser.message)
    // In case of a response parsing error we probably stopped reading the response somewhere in between, where we
    // cannot simply resume. Resetting to a known state is not easy either, so we need to close the connection to do so.
    conn.closeAllPendingWithError(parser.message)
    close(conn)
  }

  override protected def cleanClose(conn: Conn) {
    conn.closeAllPendingWithError("Server closed connection")
    super.cleanClose(conn)
  }

  protected def handleTimedOutRequests() {
    openRequests.forAllTimedOut(config.requestTimeout) { requestRecord =>
      log.warn("Request to '{}' timed out, closing the connection", requestRecord.request.uri)
      requestRecord.conn.closeAllPendingWithError("Request timed out")
      close(requestRecord.conn)
    }
  }

  override protected def reapConnection(conn: Conn) {
    conn.closeAllPendingWithError("Connection closed due to idle timeout")
    super.reapConnection(conn)
  }

  protected def openRequestCount = openRequests.size

  private class HttpConnectionImpl(conn: ClientConnection) extends HttpConnection with RequestPreparer {
    protected def userAgentHeader = config.userAgentHeader

    def send(request: HttpRequest) = {
      // we "disable" the akka future timeout, since we rely on our own logic
      val future = new DefaultCompletableFuture[HttpResponse](Long.MaxValue)
      send(request, Actor.actorOf(new ResponseBufferingActor(future)).start())
      future
    }

    def send(request: HttpRequest, receiver: ActorRef, context: Option[Any] = None) {
      HttpRequest.verify(request)
      log.debug("Enqueueing valid HttpRequest as raw request")
      val responder: AnyRef => Unit = context match {
        case Some(ctx) => receiver ! (_, ctx)
        case None => receiver ! _
      }
      self ! Send(conn, request, prepareRequest(request, conn.host, conn.port), responder)
    }

    def startRequestStream(requestStart: ChunkedRequestStart) = {
      throw new RuntimeException("Not yet")
    }

    def close() {
      self ! Close(conn)
    }
  }
}

private[can] class ResponseBufferingActor(future: CompletableFuture[HttpResponse]) extends Actor {
  protected def receive = {
    case response: HttpResponse => future.completeWithResult(response)
    case exception: HttpClientException => future.completeWithException(exception)
  }
}

class HttpClientException(message: String) extends RuntimeException(message)