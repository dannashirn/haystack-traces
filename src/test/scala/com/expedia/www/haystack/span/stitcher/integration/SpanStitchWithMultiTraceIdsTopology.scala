/*
 *  Copyright 2017 Expedia, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.expedia.www.haystack.span.stitcher.integration

import java.util.{List => JList}

import com.expedia.open.tracing.stitch.StitchedSpan
import com.expedia.www.haystack.span.stitcher.StreamTopology
import com.expedia.www.haystack.span.stitcher.config.entities.{ChangelogConfiguration, KafkaConfiguration, StitchConfiguration}
import org.apache.kafka.streams.integration.utils.IntegrationTestUtils
import org.apache.kafka.streams.processor.FailOnInvalidTimestamp
import org.apache.kafka.streams.processor.TopologyBuilder.AutoOffsetReset
import org.apache.kafka.streams.{KeyValue, StreamsConfig}

import scala.collection.JavaConversions._
import scala.concurrent.duration._

class SpanStitchWithMultiTraceIdsTopology extends BaseIntegrationTestSpec {
  private val MAX_CHILD_SPANS = 5
  private val TRACE_ID_1 = "traceid-1"
  private val TRACE_ID_2 = "traceid-2"
  private val SPAN_ID_PREFIX_1 = TRACE_ID_1 + "span-id-"
  private val SPAN_ID_PREFIX_2 = TRACE_ID_2 + "span-id-"

  "Stitch Span Topology" should {
    s"consume spans from input '$INPUT_TOPIC' and stitch them together" in {
      Given("a set of spans with two different traceIds and stitching/kafka specific configurations")
      val stitchConfig = StitchConfiguration(
        INITIAL_STORE_CAPACITY,
        MAX_STITCHED_RECORDS_IN_MEM,
        PUNCTUATE_INTERVAL_MS,
        SPAN_STITCH_WINDOW_MS,
        AUTO_COMMIT_INTERVAL_MS)
      val kafkaConfig = KafkaConfiguration(new StreamsConfig(STREAMS_CONFIG),
        OUTPUT_TOPIC,
        INPUT_TOPIC,
        AutoOffsetReset.EARLIEST,
        new FailOnInvalidTimestamp,
        ChangelogConfiguration(enabled = false))

      When(s"spans are produced in '$INPUT_TOPIC' topic async, and kafka-streams topology is started")
      produceSpansAsync(MAX_CHILD_SPANS,
        2.seconds,
        List(SpanDescription(TRACE_ID_1, SPAN_ID_PREFIX_1), SpanDescription(TRACE_ID_2, SPAN_ID_PREFIX_2)))
      val topology = new StreamTopology(kafkaConfig, stitchConfig)
      topology.start()

      Then(s"we should read two stitch span objects with differet traceIds from '$OUTPUT_TOPIC' topic")
      val result: JList[KeyValue[String, StitchedSpan]] =
        IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(RESULT_CONSUMER_CONFIG, OUTPUT_TOPIC, 2, MAX_WAIT_FOR_OUTPUT_MS)

      validateStitchedSpan(result, MAX_CHILD_SPANS)

      topology.close() shouldBe true
    }
  }

  // validate the received records
  private def validateStitchedSpan(records: JList[KeyValue[String, StitchedSpan]], childSpanCount: Int) = {
    // expect only one stitched span object
    records.size() shouldBe 2

    // both traceIds should be present as different stitched span objects
    records.map(_.key) should contain allOf (TRACE_ID_1, TRACE_ID_2)

    records.foreach(record => {
      record.key match {
        case TRACE_ID_1 => validateChildSpans(record.value, TRACE_ID_1, SPAN_ID_PREFIX_1, MAX_CHILD_SPANS)
        case TRACE_ID_2 => validateChildSpans(record.value, TRACE_ID_2, SPAN_ID_PREFIX_2, MAX_CHILD_SPANS)
      }
    })
  }
}
