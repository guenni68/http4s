package org.http4s
package util.middleware

import scalaz.stream.Process._
import scalaz.concurrent.Task

import Header.{`Content-Length`, `Content-Encoding`, `Accept-Encoding`}
import util.Gzipper

import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 11/30/13
 */
object GZip extends Logging {

  // Used to 'flush' the process1 at the end of the stream
  private [GZip] object FinalChunk extends TrailerChunk

  /** Streaming GZip Process1 */
  def streamingGZip(buffersize: Int): Process1[Chunk, Chunk] = {
//    val bytes = new ByteArrayOutputStream(0)
//    val gzip = new GZIPOutputStream(bytes, buffersize, false)
    val gzip = new Gzipper(buffersize)

    def getBodyChunk = BodyChunk(gzip.getBytes())

    def folder(chunk: Chunk): Process1[Chunk, Chunk] = chunk match {
      case c: BodyChunk =>
        gzip.write(c.toArray)
        if (gzip.size() < 0.8*buffersize) await(Get[Chunk])(folder) // Wait for ~80% buffer capacity
        else Emit(getBodyChunk::Nil, await(Get[Chunk])(folder))      // Emit a chunk

      case t: TrailerChunk =>
        gzip.finish()
        val c = getBodyChunk
        if (t eq FinalChunk) emit(c) else emitAll(c::t::Nil)
    }
    await(Get[Chunk])(folder)
  }
  
  // TODO: It could be possible to look for Task.now type bodies, and change the Content-Length header after
  // TODO      zipping and buffering all the input. Just a thought.
  def apply(route: HttpService, buffersize: Int = 512): HttpService = {

    def request(req: Request): Task[Response] = {
      //Header.`Accept-Encoding` req.prelude.headers
      val t = route(req)
      req.headers.get(`Accept-Encoding`).fold(t){ h =>
        if (h.acceptsEncoding(ContentCoding.gzip) || h.acceptsEncoding(ContentCoding.`x-gzip`)) t.map { resp =>
          // Accepts encoding. Make sure Content-Encoding is not set and transform body and add the header
          if (resp.prelude.headers.get(`Content-Encoding`).isEmpty) {
            logger.trace("GZip middleware encoding content")
            val b = (resp.body ||| emit(FinalChunk)).pipe(streamingGZip(buffersize))
            resp.dropHeader(`Content-Length`)
              .addHeader(`Content-Encoding`(ContentCoding.gzip))
              .copy(body = b)
          }
          else resp  // Don't touch it, Content-Encoding already set
        } else t
      }

    }

    request
  }

}
