/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnostics;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The byte bound of the agent log queue (ADR-0010 C-120 (3), N-02): queued text stays at or under 1
 * MiB (characters x 2 plus the per-record overhead); records beyond it are dropped and counted, and
 * the enqueuing thread never waits.
 */
@Tag("req:N-02")
@Tag("req:C-120")
class LogPipelineBytesTest {
  private static String text(int chars) {
    char[] value = new char[chars];
    Arrays.fill(value, 'x');
    return new String(value);
  }

  @Test
  @DisplayName("T-NFR-02a a full queue of long messages stops at 1 MiB, not at 4,096 x 8,192 chars")
  void boundedByBytes() {
    LogPipeline pipeline = new LogPipeline();
    String message = text(LogPipeline.MAX_MESSAGE_CHARS);
    for (int i = 0; i < LogPipeline.CAPACITY; i++) {
      pipeline.enqueue(LogLevel.INFO, message, null);
    }
    long perRecord = LogPipeline.estimatedBytes(message, Thread.currentThread().getName(), null);
    assertThat(pipeline.queuedBytes()).isLessThanOrEqualTo(LogPipeline.MAX_QUEUED_BYTES);
    assertThat(pipeline.queuedBytes())
        .as("filled up to the bound")
        .isGreaterThan(LogPipeline.MAX_QUEUED_BYTES - perRecord);
    assertThat(pipeline.queued())
        .isLessThan(LogPipeline.CAPACITY)
        .isEqualTo((int) (LogPipeline.MAX_QUEUED_BYTES / perRecord));
    assertThat(pipeline.dropped()).isEqualTo(LogPipeline.CAPACITY - pipeline.queued());
  }

  @Test
  @DisplayName("short records still reach the count bound; every drop is counted")
  void countBoundStays() {
    LogPipeline pipeline = new LogPipeline();
    int extra = 50;
    for (int i = 0; i < LogPipeline.CAPACITY + extra; i++) {
      pipeline.enqueue(LogLevel.INFO, "m", null);
    }
    assertThat(pipeline.queued()).isEqualTo(LogPipeline.CAPACITY);
    assertThat(pipeline.dropped()).isEqualTo(extra);
    assertThat(pipeline.queuedBytes()).isLessThanOrEqualTo(LogPipeline.MAX_QUEUED_BYTES);
  }

  @Test
  @DisplayName("the estimate counts characters x 2, overhead, and more for a throwable")
  void estimate() {
    assertThat(LogPipeline.estimatedBytes("abc", "t", null))
        .isEqualTo(LogPipeline.RECORD_OVERHEAD_BYTES + 8);
    assertThat(LogPipeline.estimatedBytes("abc", null, null))
        .isEqualTo(LogPipeline.RECORD_OVERHEAD_BYTES + 6);
    assertThat(LogPipeline.estimatedBytes("abc", "t", new RuntimeException()))
        .isEqualTo(LogPipeline.RECORD_OVERHEAD_BYTES + 8 + LogPipeline.ERROR_OVERHEAD_BYTES);
  }

  @Test
  @DisplayName("draining and discarding give the bytes back, so the log flows again")
  void bytesReturn() {
    LogPipeline pipeline = new LogPipeline();
    AgentLoggerTest.ListSink sink = new AgentLoggerTest.ListSink();
    pipeline.setSink(sink);
    String message = text(LogPipeline.MAX_MESSAGE_CHARS);
    for (int i = 0; i < 100; i++) {
      pipeline.enqueue(LogLevel.INFO, message, null);
    }
    long dropped = pipeline.dropped();
    assertThat(dropped).isPositive();
    pipeline.drain();
    assertThat(pipeline.queuedBytes()).isZero();
    assertThat(pipeline.queued()).isZero();
    assertThat(String.join("\n", sink.lines))
        .as("the drop is reported in the log itself")
        .contains(dropped + " log record(s) dropped");

    pipeline.enqueue(LogLevel.INFO, "again", null);
    assertThat(pipeline.queued()).isEqualTo(1);
    pipeline.discardQueued();
    assertThat(pipeline.queuedBytes()).isZero();
    assertThat(pipeline.queued()).isZero();
  }

  @Test
  @Tag("req:N-05")
  @DisplayName(
      "N-05: 16 threads enqueue long messages without a sink: nothing waits, bytes stay in bound")
  void neverBlocks() throws Exception {
    final LogPipeline pipeline = new LogPipeline();
    final String message = text(LogPipeline.MAX_MESSAGE_CHARS);
    int threads = 16;
    final int each = 2_000;
    final CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  for (int i = 0; i < each; i++) {
                    pipeline.enqueue(LogLevel.WARN, message, null);
                  }
                  done.countDown();
                }
              });
      thread.setDaemon(true);
      thread.start();
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).as("every enqueue returned").isTrue();
    assertThat(pipeline.queuedBytes()).isLessThanOrEqualTo(LogPipeline.MAX_QUEUED_BYTES);
    assertThat(pipeline.queued() + pipeline.dropped()).isEqualTo((long) threads * each);
  }

  @Test
  @DisplayName("argos_agent_log_dropped_total comes from the pipeline's counter")
  void metricSource() {
    long before = AgentLogger.droppedRecords();
    assertThat(before).isGreaterThanOrEqualTo(0L);
    assertThat(AgentLogger.pipeline().dropped()).isEqualTo(before);
    SelfDiagnostics snapshot =
        new SelfDiagnosticsCollector(null, new AgentCounters(), null).snapshot();
    assertThat(snapshot.toJson()).contains("\"" + SelfDiagnosticsCollector.LOG_DROPPED + "\"");
  }
}
