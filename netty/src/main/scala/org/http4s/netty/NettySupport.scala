package org.http4s
package netty

import io.netty.channel.{ChannelInboundHandlerAdapter, ChannelOption, ChannelHandlerContext}

import java.net.{InetSocketAddress}
import java.util.Map.Entry
import java.lang.Iterable

import scalaz.concurrent.Task
import scalaz.stream.Process
import Process.{await, emit, repeatEval, End}
import scalaz.{-\/, \/-}

import io.netty.buffer.ByteBuf
import scala.collection.mutable.ListBuffer
import com.typesafe.scalalogging.slf4j.Logging
import io.netty.util.ReferenceCountUtil
import org.http4s.netty.utils.{ChunkHandler, NettyOutput}

/**
 * @author Bryce Anderson
 *         Created on 11/28/13
 */

abstract class NettySupport[MsgType, RequestType <: MsgType] extends ChannelInboundHandlerAdapter with Logging {

  import NettySupport.InvalidStateException

  def serverSoftware: ServerSoftware

  def service: HttpService

  def localAddress: InetSocketAddress

  def remoteAddress: InetSocketAddress

  /** Method that turns a netty request into a Http4s request
    *
    * @param ctx the channels ChannelHandlerContext
    * @param req request head
*     @throws  InvalidStateException if now is not an appropriate time to receive a request
    * @return http4s request
    */
  @throws[InvalidStateException]
  protected def toRequest(ctx: ChannelHandlerContext, req: RequestType): Request

  protected def renderResponse(ctx: ChannelHandlerContext, req: RequestType, response: Response): Task[_]

  /** deal with incoming messages which belong to this service
    * @param ctx ChannelHandlerContext of the pipeline
    * @param msg received message
    */
  def onHttpMessage(ctx: ChannelHandlerContext, msg: AnyRef): Unit

  /** Shortcut for raising invalid state Exceptions
    * @param msg Message for the exception
    * @return Nothing
    */
  def invalidState(msg: String): Nothing = throw new InvalidStateException(msg)

  /** Forward the message, and release the object once we are finished
    * @param ctx ChannelHandlerContext for the channel
    * @param msg Message to be forwarded
    */
  final override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = {
    try onHttpMessage(ctx, msg)
    finally  ReferenceCountUtil.release(msg)
  }

  /** Disable the netty read process
    *
    * @param ctx channel handler context for this channel
    */
  protected def disableRead(ctx: ChannelHandlerContext): Unit = {
    ctx.channel().config().setOption(ChannelOption.AUTO_READ, new java.lang.Boolean(false))
  }

  /** Enable the netty read process
    *
    * @param ctx channel handler context for this channel
    */
  protected def enableRead(ctx: ChannelHandlerContext): Unit = {
    ctx.channel().config().setOption(ChannelOption.AUTO_READ, new java.lang.Boolean(true))
  }

  protected def runHttpRequest(ctx: ChannelHandlerContext, req: RequestType) {
    logger.trace("Starting http request.")

    val request = toRequest(ctx, req)
    val task = try service(request)
    catch { // TODO: don't rely on exceptions for bad requests?
      case m: MatchError => Status.NotFound(request)
      case e: Throwable =>
        logger.error("Received error on route", e)
        Status.InternalServerError()
    }

    task.flatMap(renderResponse(ctx, req, _)).runAsync {
      case -\/(t) =>
        logger.error("Final Task results in an Exception.", t)
        ctx.channel().close()

      // Make sure we are allowing reading at the end of the request
      case \/-(_) =>  if (ctx.channel.isOpen) enableRead(ctx)
    }
  }


}

object NettySupport {
  /** Method for converting collections of headers to http4s HeaderCollection
    * @param headers headers container
    * @return a collection of the raw http4s headers
    */
  def toHeaders(headers: Iterable[Entry[String, String]]): HeaderCollection = {
    val lb = new ListBuffer[Header]
    val i = headers.iterator()
    while (i.hasNext) { val n = i.next(); lb += Header(n.getKey, n.getValue) }
    HeaderCollection(lb.result())
  }

  /** Convert a Netty ByteBuf to a BodyChunk
    *
    * @param buff netty ByteBuf which to convert
    * @return
    */
  def buffToBodyChunk(buff: ByteBuf): BodyChunk = {
    val arr = new Array[Byte](buff.readableBytes())
    buff.getBytes(0, arr)
    BodyChunk(arr)
  }

  def getStream(manager: ChunkHandler): Process[Task, Chunk] = {
    val t = Task.async[Chunk](cb => manager.request(cb))
    repeatEval(t)
  }

  class InvalidStateException(msg: String) extends Exception(msg)
}

