/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exporter SPI: JSONL file, noop, grpc slot, bounded queue and sender thread (10 §10.1-10.2). */
class ExportTest {
  @TempDir File dir;

  private final Dictionary dictionary = new Dictionary();
  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final TracerImpl tracer =
      new TracerImpl(
          dictionary,
          new TracerImpl.Sink() {
            @Override
            public void accept(Transaction tx) {
              ended.add(tx);
            }
          },
          TestControls.newControl(),
          new TxIdGenerator(),
          1000,
          100);

  private Transaction transaction(String service, String sql) {
    TraceContext ctx = tracer.begin(Kind.HTTP, service, 1_000_000);
    tracer.setServiceName(ctx, service, true);
    tracer.setHttp(ctx, "GET", "/orders/1", "127.0.0.1", null);
    int seq = tracer.stepStart(ctx, StepType.SQL, tracer.dict(DictKind.SQL, sql), 0, 1_500_000);
    tracer.stepDetail(ctx, seq, "line1\nline2\t\"quoted\" \\ \u0001");
    tracer.stepEnd(ctx, seq, 0, false, 2, 3_000_000);
    tracer.end(ctx, 200, null, null, null, 4_250_000);
    return ended.get(ended.size() - 1);
  }

  @Test
  @Tag("req:F-A01")
  @DisplayName(
      "T-AG-010 (PoC) JSONL exporter writes one escaped JSON object per transaction with steps")
  void jsonl() throws Exception {
    File file = new File(dir, "out/tx.jsonl");
    JsonlFileExporter exporter = new JsonlFileExporter(file, 1 << 20, dictionary);
    Transaction tx = transaction("GET /orders/{id}", "select * from orders where id = ?");
    exporter.export(Collections.singletonList(tx));
    exporter.flush();
    exporter.close();
    List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    assertThat(lines).hasSize(1);
    String line = lines.get(0);
    assertThat(line)
        .startsWith("{\"tx_id\":\"" + Long.toUnsignedString(tx.context().txId) + "\"")
        .contains("\"kind\":\"HTTP\"")
        .contains("\"service\":\"GET /orders/{id}\"")
        .contains("\"service_fixed\":true")
        .contains("\"elapsed_ms\":3.250")
        .contains("\"status\":200")
        .contains("\"error\":false")
        .contains("\"http_method\":\"GET\"")
        .contains("\"parent_span_id\":null")
        .contains("\"type\":\"SQL\"")
        .contains("\"name\":\"select * from orders where id = ?\"")
        .contains("\"start_offset_ms\":0.500")
        .contains("\"elapsed_ms\":1.500")
        .contains("\"rows\":2")
        .contains("\"detail\":\"line1\\nline2\\t\\\"quoted\\\" \\\\ \\u0001\"")
        .contains("\"summary\":[]")
        .endsWith("\"summary_overflow\":0}");
    assertThat(line).doesNotContain("\n");
  }

  @Test
  @DisplayName("JSONL exporter stops at exporter.file.max-mb and keeps the file valid")
  void jsonlSizeCap() throws Exception {
    File file = new File(dir, "capped.jsonl");
    JsonlFileExporter exporter = new JsonlFileExporter(file, 1500, dictionary);
    List<Transaction> batch = new ArrayList<Transaction>();
    for (int i = 0; i < 5; i++) {
      batch.add(transaction("GET /" + i, "select " + i));
    }
    exporter.export(batch);
    exporter.close();
    List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    assertThat(lines).isNotEmpty().hasSizeLessThan(5);
    assertThat(file.length()).isLessThanOrEqualTo(1500);
  }

  @Test
  @Tag("req:N-05")
  @DisplayName("TransactionQueue drops the oldest entry when full (count and byte bounds)")
  void queueBounds() {
    TransactionQueue queue = new TransactionQueue(3, 1L << 30);
    List<Transaction> all = new ArrayList<Transaction>();
    for (int i = 0; i < 5; i++) {
      Transaction tx = transaction("GET /" + i, "select 1");
      all.add(tx);
      queue.offer(tx);
    }
    assertThat(queue.size()).isEqualTo(3);
    assertThat(queue.dropped()).isEqualTo(2);
    List<Transaction> drained = new ArrayList<Transaction>();
    assertThat(queue.drainTo(drained, 10)).isEqualTo(3);
    assertThat(drained).containsExactly(all.get(2), all.get(3), all.get(4));
    assertThat(queue.size()).isZero();

    Transaction one = transaction("GET /bytes", "select 1");
    TransactionQueue small = new TransactionQueue(100, one.estimatedBytes() * 2 + 1);
    small.offer(one);
    small.offer(transaction("GET /bytes2", "select 1"));
    small.offer(transaction("GET /bytes3", "select 1"));
    assertThat(small.size()).isEqualTo(2);
    assertThat(small.dropped()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "exporter registry: noop, file (default path in the log directory), grpc slot, unknown")
  void registry() throws Exception {
    AgentInfo info = new AgentInfo("1.0", "abc", "1");
    Exporter noop = Exporters.create(context("noop"), dir);
    assertThat(noop).isInstanceOf(NoopExporter.class);
    Exporter file = Exporters.create(context("file"), dir);
    assertThat(file.describe())
        .isEqualTo("file " + new File(dir, Exporters.DEFAULT_FILE_NAME).getAbsoluteFile());
    file.close();
    // The grpc transport is not part of this build (P-3/WO-11): noop with one WARN.
    assertThat(Exporters.create(context("grpc"), dir)).isInstanceOf(NoopExporter.class);
    assertThat(Exporters.create(context("bogus"), dir)).isInstanceOf(NoopExporter.class);
    assertThat(Exporters.configuredName(AgentConfig.of(Collections.<String, String>emptyMap())))
        .isEqualTo("grpc");
    assertThat(info.version()).isEqualTo("1.0");
  }

  @Test
  @DisplayName("sender thread exports queued transactions and drains the rest at stop")
  void exportWorker() throws Exception {
    File file = new File(dir, "worker.jsonl");
    Map<String, String> values = new HashMap<String, String>();
    values.put("exporter", "file");
    values.put("exporter.file.path", file.getPath());
    TransactionQueue queue = new TransactionQueue(100, 1L << 30);
    ExportWorker worker =
        new ExportWorker(
            queue,
            new ExporterContext(AgentConfig.of(values), dictionary, new AgentInfo("1", "c", "1")),
            dir,
            2,
            10);
    worker.start();
    for (int i = 0; i < 5; i++) {
      queue.offer(transaction("GET /w" + i, "select 1"));
    }
    long deadline = System.currentTimeMillis() + 10_000;
    while (worker.exported() < 5 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    queue.offer(transaction("GET /late", "select 1"));
    worker.stop(2000);
    assertThat(worker.exported()).isEqualTo(6);
    assertThat(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)).hasSize(6);
    assertThat(worker.exporter().describe()).startsWith("file ");
  }

  private ExporterContext context(String exporter) {
    Map<String, String> values = new HashMap<String, String>();
    values.put("exporter", exporter);
    return new ExporterContext(AgentConfig.of(values), dictionary, new AgentInfo("1", "c", "1"));
  }
}
