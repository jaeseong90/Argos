/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The error stack on the wire (11 §4.3, ADR-0010 C-222 (1), C-237 (6); WO-19): {@code
 * TransactionRecord.error_top_frame_id} with its FRAME text pre-sent, and the profile's ERROR step
 * with its CLASS name and up to 30 frames, all pre-sent (11 §7).
 */
@Tag("req:F-A08")
class ErrorFrameAssemblerTest {
  private final TraceRuntime runtime = runtime();
  private final DictionaryCache cache = new DictionaryCache(runtime.dictionary());
  private final BatchAssembler assembler = new BatchAssembler(runtime.dictionary(), cache);

  private static TraceRuntime runtime() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    values.put("sampling.profile.normal-rate", "1.0");
    return TraceRuntime.create(AgentConfig.of(values), new AgentCounters());
  }

  private <T> T only(AgentQueue<QueueItem> queue, Class<T> type) {
    final List<QueueItem> out = new ArrayList<QueueItem>();
    queue.drain(
        Integer.MAX_VALUE,
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            out.add(item);
          }
        });
    assertThat(out).hasSize(1);
    return type.cast(out.get(0));
  }

  private Map<Long, DictionaryEntry> pending() {
    Map<Long, DictionaryEntry> out = new HashMap<Long, DictionaryEntry>();
    for (DictionaryEntries batch : cache.takePending()) {
      for (DictionaryEntry entry : batch.getEntriesList()) {
        out.put(entry.getHash(), entry);
      }
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-AG-059c error_top_frame_id is the line-free FRAME id of the first application frame,"
          + " pre-sent; T-AG-059b the ERROR step goes out with its CLASS name and 30 frames")
  void onTheWire() {
    TracerImpl tracer = runtime.tracerImpl();
    tracer.setAppPackages(Collections.singletonList("com.acme"));
    StackTraceElement[] frames = new StackTraceElement[40];
    for (int i = 0; i < frames.length; i++) {
      frames[i] = new StackTraceElement("org.lib.L" + i, "m", "L" + i + ".java", i + 1);
    }
    frames[35] = new StackTraceElement("com.acme.web.Orders", "get", "Orders.java", 88);
    RuntimeException e = new IllegalStateException("boom");
    e.setStackTrace(frames);
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders", 0);
    tracer.end(ctx, 500, null, null, e, 5_000_000);

    TransactionRecord record =
        assembler.record(only(runtime.queues().transaction(), Transaction.class));
    TransactionProfile profile =
        assembler.profile(only(runtime.queues().profile(), ProfileRecord.class));
    Map<Long, DictionaryEntry> sent = pending();

    long top = record.getErrorTopFrameId();
    assertThat(top).isNotZero();
    assertThat(sent.get(top).getKind()).isEqualTo(DictionaryKind.FRAME);
    assertThat(sent.get(top).getText()).isEqualTo("com.acme.web.Orders.get(Orders.java)");

    assertThat(profile.getStepsList()).hasSize(1);
    ProfileStep step = profile.getSteps(0);
    assertThat(step.getType()).isEqualTo(StepType.ERROR);
    assertThat(step.getParentSeq()).isZero();
    assertThat(step.getError()).isTrue();
    assertThat(step.getDetail()).isEmpty();
    assertThat(sent.get(step.getNameId()).getKind()).isEqualTo(DictionaryKind.CLASS);
    assertThat(sent.get(step.getNameId()).getText())
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(step.getFrameIdsList()).hasSize(30);
    assertThat(sent.get(step.getFrameIds(0)).getText()).isEqualTo("org.lib.L0.m(L0.java:1)");
    assertThat(sent.get(step.getFrameIds(29)).getText()).isEqualTo("org.lib.L29.m(L29.java:30)");
  }

  @Test
  @DisplayName("T-AG-059c no application frame: error_top_frame_id absent (0)")
  void noFrame() {
    TracerImpl tracer = runtime.tracerImpl();
    tracer.setAppPackages(Collections.singletonList("com.acme"));
    RuntimeException e = new IllegalStateException("boom");
    e.setStackTrace(new StackTraceElement[] {new StackTraceElement("org.lib.L", "m", "L.java", 1)});
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders", 0);
    tracer.end(ctx, 500, null, null, e, 5_000_000);

    TransactionRecord record =
        assembler.record(only(runtime.queues().transaction(), Transaction.class));
    assertThat(record.getErrorTopFrameId()).isZero();
  }
}
