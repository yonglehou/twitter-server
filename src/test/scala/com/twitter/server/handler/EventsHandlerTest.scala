package com.twitter.server.handler

import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.{Reader, Buf}
import com.twitter.util.events.{Sink, Event}
import com.twitter.util.{Await, Future, FuturePool, Promise, Time}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class EventsHandlerTest extends FunSuite {

  test("streaming html") {
    val p = new Promise[Stream[Event]]
    val e = Event(Event.nullType, Time.now)

    // We should only need one e, but because of our implementation of Reader
    // concatenation, using just one would block the preamble Reader.read
    // operation below.
    val stream = e #:: e #:: Await.result(p)

    val sink = new Sink {
      def event(e: Event.Type, l: Long, o: Object, d: Double, t: Long, s: Long) = ()
      def events = stream.toIterator
    }

    val controller = new EventsHandler(sink)
    val req = Request()
    // Necessary for controller to determine that this is a request for HTML.
    req.headerMap.add("User-Agent", "Mozilla")

    val res = Await.result(controller(req)).asInstanceOf[Response]
    val preamble = res.reader.read(Int.MaxValue)
    assert(preamble.isDefined)

    // We have to run this in a pool or Reader.read ends up blocking, because
    // we call Await.result in the sink events iterator. This is ok, this
    // Future is still useful for the assertion that `sink` and Response reader
    // are connected.
    val content = FuturePool.unboundedPool { res.reader.read(Int.MaxValue) }
    assert(!content.isDefined)

    // Doesn't matter that this is an exception, it's just used as a control
    // signal for resumption of `stream`'s tail.
    p.setException(new Exception)

    assert(Await.result(content).isDefined)
    res.reader.discard()
  }

  test("trace: empty sink") {
    val empty = Sink.of(mutable.ListBuffer.empty)
    val reader = TraceEventSink.serialize(empty)
    val Buf.Utf8(json) = Await.result(Reader.readAll(reader))
    assert(json == "[")
  }

  test("trace: base") {
    val sink = Sink.of(mutable.ListBuffer.empty)
    sink.event(Event.nullType, objectVal = "hello")

    val reader = TraceEventSink.serialize(sink)
    val Buf.Utf8(json) = Await.result(Reader.readAll(reader)).concat(Buf.Utf8("]"))

    val doc = Json.deserialize[Seq[Map[String, Any]]](json)
    val args = doc.head("args").asInstanceOf[Map[String, Any]]

    assert(doc.head("name") == Event.nullType.id)
    assert(args("objectVal") == "hello")
  }
}
