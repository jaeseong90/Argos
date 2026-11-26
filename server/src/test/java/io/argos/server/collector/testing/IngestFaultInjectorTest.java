/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.batch.Shaped;
import io.argos.server.collector.testing.IngestFaultInjector.Fault;
import io.argos.server.collector.testing.IngestFaultInjector.Mode;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ServerBuild;
import io.argos.server.config.TestingHooks;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link IngestFaultInjector}: the file format, the once-per-second read, the error answers with
 * their trailers, the injected quota drop and the development-build gate (18 §15, §19; WO-52).
 */
@Tag("req:DSN-18-15")
@Tag("req:N-05")
class IngestFaultInjectorTest {

  @TempDir Path dir;

  private final AtomicLong nanos = new AtomicLong(1_000);

  private Path file(String content) throws Exception {
    Path f = dir.resolve("ingest-fault");
    Files.writeString(f, content, StandardCharsets.UTF_8);
    return f;
  }

  private void tick(long seconds) {
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
  }

  private static TransactionBatch tx(int records) {
    TransactionBatch.Builder b = TransactionBatch.newBuilder();
    for (int i = 0; i < records; i++) {
      b.addRecords(TransactionRecord.newBuilder().setTxId(i));
    }
    return b.build();
  }

  @Test
  @DisplayName("T-CH-004b fault file: modes, ratio, blanks and errors")
  void parse() {
    assertThat(Fault.parse("UNAVAILABLE")).isEqualTo(new Fault(Mode.UNAVAILABLE, 1));
    assertThat(Fault.parse(" resource_exhausted : 0.25 \n"))
        .isEqualTo(new Fault(Mode.RESOURCE_EXHAUSTED, 0.25));
    assertThat(Fault.parse("Internal:1")).isEqualTo(new Fault(Mode.INTERNAL, 1));
    assertThat(Fault.parse("QUOTA:0.5")).isEqualTo(new Fault(Mode.QUOTA, 0.5));
    assertThat(Fault.parse("")).isNull();
    assertThat(Fault.parse(null)).isNull();
    assertThatThrownBy(() -> Fault.parse("SLOW")).hasMessageContaining("unknown mode");
    assertThatThrownBy(() -> Fault.parse("QUOTA:0")).hasMessageContaining("(0, 1]");
    assertThatThrownBy(() -> Fault.parse("QUOTA:1.5")).hasMessageContaining("(0, 1]");
    assertThatThrownBy(() -> Fault.parse("QUOTA:half")).hasMessageContaining("not a number");
  }

  @Test
  @DisplayName("T-CH-004b fault file read at most once per second; absent, empty or unknown is off")
  void readsOncePerSecond() throws Exception {
    Path f = file("UNAVAILABLE");
    IngestFaultInjector i = IngestFaultInjector.of(f, nanos::get, () -> 0.0);
    assertThat(i.enabled()).isTrue();
    assertThat(i.current()).isEqualTo(new Fault(Mode.UNAVAILABLE, 1));

    Files.writeString(f, "QUOTA:0.5", StandardCharsets.UTF_8);
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(999));
    assertThat(i.current())
        .as("within the second: the old fault")
        .isEqualTo(new Fault(Mode.UNAVAILABLE, 1));
    tick(1);
    assertThat(i.current()).isEqualTo(new Fault(Mode.QUOTA, 0.5));

    Files.writeString(f, "  ", StandardCharsets.UTF_8);
    tick(1);
    assertThat(i.current()).as("empty file").isNull();

    Files.writeString(f, "SLOW", StandardCharsets.UTF_8);
    tick(1);
    assertThat(i.current()).as("not understood").isNull();

    Files.writeString(f, "INTERNAL", StandardCharsets.UTF_8);
    tick(1);
    assertThat(i.current()).isEqualTo(new Fault(Mode.INTERNAL, 1));

    Files.delete(f);
    tick(1);
    assertThat(i.current()).as("absent file").isNull();
    assertThat(i.reject()).isNull();
  }

  @Test
  @DisplayName(
      "T-CH-004b error modes: UNAVAILABLE, INTERNAL, RESOURCE_EXHAUSTED with argos-retry-after-ms")
  void errors() throws Exception {
    Path f = file("UNAVAILABLE");
    IngestFaultInjector i = IngestFaultInjector.of(f, nanos::get, () -> 0.0);
    StatusRuntimeException e = i.reject();
    assertThat(e).isNotNull();
    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);

    Files.writeString(f, "INTERNAL", StandardCharsets.UTF_8);
    tick(1);
    assertThat(i.reject().getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);

    Files.writeString(f, "RESOURCE_EXHAUSTED", StandardCharsets.UTF_8);
    tick(1);
    StatusRuntimeException re = i.reject();
    assertThat(re.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(re.getTrailers().get(CollectorCalls.RETRY_AFTER_MS))
        .isEqualTo(Long.toString(IngestFaultInjector.RETRY_AFTER_MS));
    assertThat(re.getTrailers().get(CollectorCalls.REJECT_REASON))
        .as("not a limits violation: the agent retries instead of splitting")
        .isNull();

    Files.writeString(f, "QUOTA", StandardCharsets.UTF_8);
    tick(1);
    assertThat(i.reject()).as("QUOTA is not an error answer").isNull();
  }

  @Test
  @DisplayName("T-CH-004b error ratio: a batch is refused with probability ratio")
  void ratio() throws Exception {
    double[] draw = {0.3};
    IngestFaultInjector i =
        IngestFaultInjector.of(file("UNAVAILABLE:0.5"), nanos::get, () -> draw[0]);
    assertThat(i.reject()).isNotNull();
    draw[0] = 0.7;
    assertThat(i.reject()).isNull();
    draw[0] = 0.5;
    assertThat(i.reject()).as("[0, ratio) refuses").isNull();
  }

  @Test
  @DisplayName(
      "T-CH-004b QUOTA: the last round(n x ratio) records removed before the real quota, counted with it")
  void quota() throws Exception {
    IngestFaultInjector i = IngestFaultInjector.of(file("QUOTA:0.5"), nanos::get, () -> 0.0);
    List<Integer> seen = new ArrayList<>();
    Shaped<TransactionBatch> out =
        i.quota(
            (id, b) -> {
              seen.add(b.getRecordsCount());
              // The real quota keeps 4 of what it gets.
              return new Shaped<>(
                  b.toBuilder()
                      .clearRecords()
                      .addAllRecords(b.getRecordsList().subList(0, 4))
                      .build(),
                  b.getRecordsCount() - 4);
            },
            7,
            tx(10));
    assertThat(seen).as("the real quota sees the kept records only").containsExactly(5);
    assertThat(out.droppedItems()).isEqualTo(6);
    assertThat(out.batch().getRecordsList())
        .extracting(TransactionRecord::getTxId)
        .containsExactly(0L, 1L, 2L, 3L);

    MetricBatch metrics = MetricBatch.getDefaultInstance();
    Shaped<MetricBatch> passed = i.quota((id, b) -> Shaped.unchanged(b), 7, metrics);
    assertThat(passed.droppedItems()).as("other kinds pass").isZero();

    IngestFaultInjector off = IngestFaultInjector.NONE;
    Shaped<TransactionBatch> none = off.quota((id, b) -> Shaped.unchanged(b), 7, tx(10));
    assertThat(none.droppedItems()).isZero();
    assertThat(none.batch().getRecordsCount()).isEqualTo(10);

    assertThat(IngestFaultInjector.dropCount(10, 0.5)).isEqualTo(5);
    assertThat(IngestFaultInjector.dropCount(1, 0.4)).isZero();
    assertThat(IngestFaultInjector.dropCount(3, 1)).isEqualTo(3);
  }

  private static ArgosProperties properties(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("argos", ArgosProperties.class);
  }

  @Test
  @DisplayName("T-CH-004b argos.testing.ingest-fault-file: development builds only, part of anySet")
  void gate() throws Exception {
    Path f = file("UNAVAILABLE");
    ArgosProperties set = properties(Map.of("argos.testing.ingest-fault-file", f.toString()));
    assertThat(set.testing().anySet()).isTrue();
    assertThat(set.testing().ingestFaultFile()).isEqualTo(f.toString());

    IngestFaultInjector dev =
        IngestFaultInjector.from(
            new TestingHooks(set, new ServerBuild("0.1.0-SNAPSHOT", "c", "1")));
    assertThat(dev.enabled()).isTrue();
    assertThat(dev.reject()).isNotNull();

    IngestFaultInjector release =
        IngestFaultInjector.from(new TestingHooks(set, new ServerBuild("1.0.0", "c", "1")));
    assertThat(release).isSameAs(IngestFaultInjector.NONE);
    assertThat(release.reject()).isNull();

    IngestFaultInjector unknown =
        IngestFaultInjector.from(new TestingHooks(set, new ServerBuild(null, null, null)));
    assertThat(unknown).isSameAs(IngestFaultInjector.NONE);

    ArgosProperties unset = properties(Map.of());
    assertThat(
            IngestFaultInjector.from(
                new TestingHooks(unset, new ServerBuild("0.1.0-SNAPSHOT", "c", "1"))))
        .isSameAs(IngestFaultInjector.NONE);
  }
}
