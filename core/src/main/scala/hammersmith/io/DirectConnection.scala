/**
 * Copyright (c) 2011-2013 Brendan W. McAdams <http://evilmonkeylabs.com>
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
 *
 */
package hammersmith

import java.net.InetSocketAddress
import akka.pattern.{ ask, pipe }
import akka.actor._

import akka.io.{Inet, IO => IOFaçade, Tcp}
import akka.io.Tcp._
import akka.event.Logging
import akka.io.Tcp.Connected
import akka.io.Tcp.Received
import akka.io.Tcp.Connect
import akka.io.Tcp.CommandFailed
import hammersmith.wire.{MongoClientMessage, MongoMessage}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 *
 * Direct Connection to a single mongod (or mongos) instance
 *
 */
// TODO - Should there be one handler instance per connection? Or a pool of handlers/single handler shared? (One message at a time as actor...)
case class InitializeConnection(addr: InetSocketAddress)

/**
 * The actor that actually proxies connection
 */
class DirectMongoDBConnector(val serverAddress: InetSocketAddress) extends Actor with Stash {
  val log = Logging(context.system, this)
  val tcpManager = IOFaçade(Tcp)(context.system)

  implicit val byteOrder = java.nio.ByteOrder.LITTLE_ENDIAN

  /* this is an odd one. it seems iteratees from the old IO package ARENT deprecated
   * but they haven't been ported forward/documented in the NEW IO. I see no way in the NEW
   * IO to get that behavior however...
   * I somewhat shamelessly stole some of the model of Iteratee use from Raiku's code ( as well
   * as the Iteratee code from a previous pass at moving Hammersmith to AkkaIO ) */
  protected val state = IO.IterateeRef.async

  private var connection: Option[ActorRef] = None

  override def preStart = {
    log.debug(s"Starting up a Direct Connection Actor to connect to '$serverAddress'")
    // todo - inherit keepalive setting from connection request
    tcpManager ! Connect(serverAddress, options = List(SO.KeepAlive(true), SO.TcpNoDelay(true)))
  }

  override def postStop = {
    tcpManager ! Close
    // reset the Iteratee state
    state(IO.EOF)
  }

  def receive = {
    case Connected(`serverAddress`, localAddress) =>
      /**
       * I believe ultimately we should register a separate actor for all reads;
       * If we use ourself as the listener we'll end up with a massive bandwidth limit as
       * the mailbox dequeues one at a time
       * However, then dispatching responses to the original requester becomes hard
       * w/o sharing state.
       * This also effectively limits some of our rate on the individual connection.
       * While the API might let it APPEAR we are pushing lots of writes while
       * doing lots of reads off the same connection, that's a false view from NIO...
       * it all buffers under the covers.
       */
      sender ! Register(self)
      log.debug(s"Established a direct connection to MongoDB at '$serverAddress'")
      connection = Some(sender)
      // dequeue any stashed messages
      unstashAll()
    case Received(bytes) =>
      state(IO.Chunk(bytes))
      log.debug("Decoding bytestream")
      val msg = for {
        lenBytes <- IO take(4) // 4 bytes, if aligned will be int32 length of following doc.
        len = lenBytes.iterator.getInt
        header <- IO take (16)
        frame <- IO take(len - 16 - 4) // length of total doc includes itself with BSON, so don't overflow.
      } yield MongoMessage(header.iterator, frame.iterator)
      log.info("Mongo Message: " + msg)
    case write: MongoClientMessage => connection match {
      case None =>
        // stash the message for later
        stash()
      case Some(conn) =>
        // TODO - we need to sort out how we'll handle "blocking" type requests e.g. that need a reply ... queue their future?
        // TODO - IMPLEMENT ME
        throw new UnsupportedOperationException("Write Support not yet implemented...")
    }

    case CommandFailed(cmd) =>
      // TODO - We need to handle backpressure/buffering of NIO ... which Akka expects us to do
      // this is related to that, I believe.
      log.error("Network Command Failed: " + cmd.failureMessage)
    case ConfirmedClosed =>
      log.debug(s"Confirmed closing of connection to '$serverAddress'")
    case Aborted =>
      log.error(s"Aborted connection to '$serverAddress'")
      // todo - more discrete exceptions
      throw new MongoException(s"Connection to '$serverAddress' aborted.")
    case PeerClosed =>
      log.error(s"Peer connection to '$serverAddress' disconnected unexpectedly.")
      // todo - more discrete exceptions
      throw new MongoException(s"Peer connection to '$serverAddress' disconnected unexpectedly.")
    case ErrorClosed(cause) =>
      log.error(s"Error in connection to '$serverAddress'; connection closed. Cause: '$cause'")
      // todo - more discrete exceptions
      throw new MongoException(s"Error in connection to '$serverAddress'; connection closed. Cause: '$cause'")

  }

  def send(cmd: Tcp.Command) = connection match {
    case Some(conn) =>
      conn ! cmd
    case None =>
      throw new IllegalStateException("No connection actor registered; cannot send.")
  }
}


