/*
 * Copyright 2012 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.zipkin.storage.util

import com.twitter.conversions.time._
import com.twitter.logging.Logger
import com.twitter.util.Await
import com.twitter.zipkin.common._
import com.twitter.zipkin.query.Trace
import com.twitter.zipkin.storage.{TraceIdDuration, SpanStore}
import java.nio.ByteBuffer

class SpanStoreValidator(
  newSpanStore: => SpanStore,
  log: Logger = Logger.get("ValidateSpanStore")
) {
  val ep = Endpoint(123, 123, "service")

  def binaryAnnotation(key: String, value: String) =
    BinaryAnnotation(key, ByteBuffer.wrap(value.getBytes), AnnotationType.String, Some(ep))

  val spanId = 456
  val ann1 = Annotation(1, "cs", Some(ep))
  val ann2 = Annotation(2, "sr", None)
  val ann3 = Annotation(2, "custom", Some(ep))
  val ann4 = Annotation(2, "custom", Some(ep))
  val ann5 = Annotation(5, "custom", Some(ep))
  val ann6 = Annotation(6, "custom", Some(ep))
  val ann7 = Annotation(7, "custom", Some(ep))
  val ann8 = Annotation(8, "custom", Some(ep))

  val span1 = Span(123, "methodcall", spanId, None, List(ann1, ann3),
    List(binaryAnnotation("BAH", "BEH")))
  val span2 = Span(123, "methodcall", spanId, None, List(ann2),
    List(binaryAnnotation("BAH2", "BEH2")))
  val span3 = Span(123, "methodcall", spanId, None, List(ann2, ann3, ann4),
    List(binaryAnnotation("BAH2", "BEH2")))
  val span4 = Span(999, "methodcall", spanId, None, List(ann6, ann7),
    List())
  val span5 = Span(999, "methodcall", spanId, None, List(ann5, ann8),
    List(binaryAnnotation("BAH2", "BEH2")))

  val spanEmptySpanName = Span(123, "", spanId, None, List(ann1, ann2), List())
  val spanEmptyServiceName = Span(123, "spanname", spanId, None, List(), List())

  val mergedSpan = Span(123, "methodcall", spanId, None,
    List(ann1, ann2), List(binaryAnnotation("BAH2", "BEH2")))

  def resetAndLoadStore(spans: Seq[Span]): SpanStore = {
    val store = newSpanStore
    Await.result(store(spans))
    store
  }

  private[this] var tests: Map[String, (() => Unit)] = Map.empty
  private[this] def test(name: String)(f: => Unit) {
    tests += (name -> f _)
  }

  def validate {
    val spanStoreName = newSpanStore.getClass.getName.split('.').last
    val results = tests map { case (name, f) =>
      println("validating %s: %s".format(spanStoreName, name))
      try {
        f(); println("  pass")
        true
      } catch { case e: Throwable =>
        println("fail")
        log.error(e, "validation failed")
        false
      }
    }

    val passedCount = results.count(x => x)
    println("%d / %d passed.".format(passedCount, tests.size))

    if (passedCount < tests.size) {
      println("Failed tests for %s:".format(spanStoreName))
      results.zip(tests) collect { case (result, (name, _)) if !result => println(name) }
    }

    assert(results.count(x => x) == tests.size)
  }

  test("get by trace id") {
    val store = resetAndLoadStore(Seq(span1))
    val spans = Await.result(store.getSpansByTraceId(span1.traceId))
    assert(spans.size == 1)
    assert(spans.head == span1)
  }

  test("get by trace ids") {
    val span666 = Span(666, "methodcall2", spanId, None, List(ann2),
      List(binaryAnnotation("BAH2", "BEH2")))

    val store = resetAndLoadStore(Seq(span1, span666))
    val actual1 = Await.result(store.getSpansByTraceIds(Seq(span1.traceId)))
    assert(!actual1.isEmpty)

    val trace1 = Trace(actual1(0))
    assert(!trace1.spans.isEmpty)
    assert(trace1.spans(0) == span1)

    val actual2 = Await.result(store.getSpansByTraceIds(Seq(span1.traceId, span666.traceId)))
    assert(actual2.size == 2)

    val trace2 = Trace(actual2(0))
    assert(!trace2.spans.isEmpty)
    assert(trace2.spans(0) == span1)

    val trace3 = Trace(actual2(1))
    assert(!trace3.spans.isEmpty)
    assert(trace3.spans(0) == span666)
  }

  test("get by trace ids returns an empty list if nothing is found") {
    val store = resetAndLoadStore(Seq())
    val spans = Await.result(store.getSpansByTraceIds(Seq(54321))) // Nonexistent span
    assert(spans.isEmpty)
  }

  // test("alter TTL on a span") {
  //   val store = resetAndLoadStore(Seq(span1))
  //   Await.result(store.setTimeToLive(span1.traceId, 1234.seconds))
  //   assert(Await.result(store.getTimeToLive(span1.traceId)) == 1234.seconds)
  // }

  test("get spans by name") {
    val store = resetAndLoadStore(Seq(span1))
    assert(Await.result(store.getSpanNames("service")) == Set(span1.name))
  }

  test("get service names") {
    val store = resetAndLoadStore(Seq(span1))
    println(Await.result(store.getAllServiceNames))
    assert(Await.result(store.getAllServiceNames) == span1.serviceNames)
  }

  test("get trace ids by name") {
    val store = resetAndLoadStore(Seq(span1))
    println(75)
    assert(Await.result(store.getTraceIdsByName("service", None, 3, 1)).head.traceId == span1.traceId)
    assert(Await.result(store.getTraceIdsByName("service", Some("methodcall"), 3, 1)).head.traceId == span1.traceId)

    assert(Await.result(store.getTraceIdsByName("badservice", None, 3, 1)).isEmpty)
    assert(Await.result(store.getTraceIdsByName("service", Some("badmethod"), 3, 1)).isEmpty)
    assert(Await.result(store.getTraceIdsByName("badservice", Some("badmethod"), 3, 1)).isEmpty)
  }

  test("get traces duration") {
    val store = resetAndLoadStore(Seq(span4))
    assert(Await.result(store.getTracesDuration(Seq(999))) == Seq(TraceIdDuration(999, 1, 6)))

    Await.result(store.apply(Seq(span5)))
    println(Await.result(store.getTracesDuration(Seq(999))))
    assert(Await.result(store.getTracesDuration(Seq(999))) == Seq(TraceIdDuration(999, 3, 5)))
  }

  test("get trace ids by annotation") {
    val store = resetAndLoadStore(Seq(span1))

    // fetch by time based annotation, find trace
    val res1 = Await.result(store.getTraceIdsByAnnotation("service", "custom", None, 100, 3))
    println(res1)
    assert(res1.head.traceId == span1.traceId)

    // should not find any traces since the core annotation doesn't exist in index
    val res2 = Await.result(store.getTraceIdsByAnnotation("service", "cs", None, 100, 3))
    assert(res2.isEmpty)

    // should find traces by the key and value annotation
    val res3 = Await.result(store.getTraceIdsByAnnotation("service", "BAH", Some(ByteBuffer.wrap("BEH".getBytes)), 100, 3))
    assert(res3.head.traceId == span1.traceId)
  }

  test("limit on annotations") {
    val store = resetAndLoadStore(Seq(span1, span2, span3, span4, span5))

    val res1 = Await.result(store.getTraceIdsByAnnotation("service", "custom", None, 100, limit = 3))
    assert(res1.length == 3)
    assert(res1.head.traceId == span5.traceId)
  }

  test("wont index empty service names") {
    val store = resetAndLoadStore(Seq(spanEmptyServiceName))
    assert(Await.result(store.getAllServiceNames).isEmpty)
  }

  test("wont index empty span names") {
    val store = resetAndLoadStore(Seq(spanEmptySpanName))
    assert(Await.result(store.getSpanNames(spanEmptySpanName.name)).isEmpty)
  }
}
