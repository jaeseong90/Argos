/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TraceIds;
import io.argos.agent.core.trace.Transaction;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code exporter=file}: one JSON object per line per transaction (WO-02 PoC and tests, T-AG-010).
 * The file is appended to and capped at {@code exporter.file.max-mb} (256 MB by default); beyond
 * the cap transactions are counted and dropped. Field reference: {@link #write(Transaction)}.
 */
public final class JsonlFileExporter implements Exporter {
  private final File file;
  private final long maxBytes;
  private final Dictionary dictionary;
  private final OutputStream out;
  private long size;
  private long droppedForSize;

  JsonlFileExporter(File file, long maxBytes, Dictionary dictionary) throws IOException {
    this.file = file.getAbsoluteFile();
    this.maxBytes = maxBytes;
    this.dictionary = dictionary;
    File parent = this.file.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
      throw new IOException("cannot create directory " + parent);
    }
    this.out = new BufferedOutputStream(new FileOutputStream(this.file, true), 64 * 1024);
    this.size = this.file.length();
  }

  @Override
  public void export(List<Transaction> batch) throws IOException {
    for (Transaction tx : batch) {
      byte[] line = (write(tx) + "\n").getBytes(StandardCharsets.UTF_8);
      if (size + line.length > maxBytes) {
        if (droppedForSize++ == 0) {
          AgentLogger.warn(
              "JSONL exporter: {} reached exporter.file.max-mb ({} bytes); further transactions"
                  + " are dropped",
              file,
              maxBytes);
        }
        continue;
      }
      out.write(line);
      size += line.length;
    }
  }

  /**
   * The JSON line of {@code tx}. Fields: {@code tx_id} (decimal string, 64-bit), {@code trace_id},
   * {@code span_id}, {@code parent_span_id} (hex), {@code kind}, {@code service}, {@code
   * service_fixed}, {@code start_epoch_ms}, {@code elapsed_ms}, {@code status}, {@code error},
   * {@code error_class}, {@code error_message}, {@code http_method}, {@code path}, {@code
   * remote_ip}, {@code user_id}, {@code thread}, {@code virtual_thread}, {@code flags}, {@code
   * steps[]} ({@code seq}, {@code parent}, {@code type}, {@code name_id}, {@code name}, {@code
   * target_id}, {@code target}, {@code start_offset_ms}, {@code elapsed_ms}, {@code status}, {@code
   * error}, {@code rows}, {@code fetch_ms}, {@code detail}, {@code stack[]}), {@code summary[]}
   * ({@code type}, {@code name}, {@code count}, {@code total_ms}, {@code max_ms}, {@code errors})
   * and {@code summary_overflow}. Times are milliseconds with microsecond precision.
   */
  String write(Transaction tx) {
    TraceContext ctx = tx.context();
    StringBuilder text = new StringBuilder(512);
    JsonWriter json = new JsonWriter(text).beginObject();
    json.field("tx_id", Long.toUnsignedString(ctx.txId))
        .field("trace_id", TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo))
        .field("span_id", TraceIds.hex16(ctx.spanId))
        .field("parent_span_id", ctx.parentSpanId == 0 ? null : TraceIds.hex16(ctx.parentSpanId))
        .field("kind", Kind.name(ctx.kind))
        .field("service", ctx.serviceName)
        .field("service_fixed", ctx.serviceFixed)
        .field("start_epoch_ms", ctx.startEpochMs)
        .millis("elapsed_ms", tx.elapsedNanos())
        .field("status", ctx.status)
        .field("error", ctx.error)
        .field("error_class", tx.errorClass())
        .field("error_message", tx.errorMessage())
        .field("http_method", tx.httpMethod())
        .field("path", tx.path())
        .field("remote_ip", tx.remoteIp())
        .field("user_id", tx.userId())
        .field("thread", tx.threadName())
        .field("virtual_thread", ctx.virtualThread);
    json.beginArray("flags");
    int flags = tx.flags();
    if ((flags & Transaction.FLAG_STREAMING) != 0) {
      json.value("streaming");
    }
    if ((flags & Transaction.FLAG_TIMEOUT) != 0) {
      json.value("timeout");
    }
    if ((flags & Transaction.FLAG_LEAKED) != 0) {
      json.value("leaked");
    }
    if ((flags & Transaction.FLAG_SUMMARIZED) != 0) {
      json.value("summarized");
    }
    json.endArray();
    StepBuffer steps = tx.steps();
    json.beginArray("steps");
    int count = steps.size();
    for (int seq = 0; seq < count; seq++) {
      long nameId = steps.nameId(seq);
      long targetId = steps.targetId(seq);
      json.beginObject()
          .field("seq", seq)
          .field("parent", steps.parent(seq))
          .field("type", StepType.name(steps.type(seq)))
          .field("name_id", Long.toUnsignedString(nameId))
          .field("name", dictionary.text(nameId))
          .field("target_id", Long.toUnsignedString(targetId))
          .field("target", dictionary.text(targetId))
          .millis("start_offset_ms", steps.startOffsetNanos(seq))
          .millis("elapsed_ms", steps.elapsedNanos(seq))
          .field("status", steps.status(seq))
          .field("error", steps.error(seq))
          .field("rows", steps.rows(seq))
          .millis("fetch_ms", steps.fetchNanos(seq))
          .field("detail", steps.detail(seq));
      long[] stack = steps.stack(seq);
      if (stack != null) {
        json.beginArray("stack");
        for (long frame : stack) {
          String name = dictionary.text(frame);
          json.value(name == null ? Long.toUnsignedString(frame) : name);
        }
        json.endArray();
      }
      json.endObject();
    }
    json.endArray();
    json.beginArray("summary");
    for (int key = 0; key < steps.summaryKeys(); key++) {
      json.beginObject()
          .field("type", StepType.name(steps.summaryType(key)))
          .field("name", dictionary.text(steps.summaryNameId(key)))
          .field("count", steps.summaryCount(key))
          .millis("total_ms", steps.summaryTotalNanos(key))
          .millis("max_ms", steps.summaryMaxNanos(key))
          .field("errors", steps.summaryErrors(key))
          .endObject();
    }
    json.endArray();
    json.field("summary_overflow", steps.summaryOverflow());
    json.endObject();
    return text.toString();
  }

  @Override
  public void flush() throws IOException {
    out.flush();
  }

  @Override
  public void close() {
    try {
      out.close();
    } catch (IOException e) {
      AgentLogger.warn("JSONL exporter: closing {} failed: {}", file, e.toString());
    }
  }

  @Override
  public String describe() {
    return "file " + file;
  }
}
