/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.ProfileSteps;
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
 * {@code test.exporter=file:<dir>} (18 §5.4, development builds only, T-BLD-010): one JSON object
 * per line per transaction (tests, T-AG-010). The file is appended to and capped ({@code
 * Exporters.FILE_MAX_MB}, 256 MB); beyond the cap transactions are counted and dropped. Field
 * reference: {@link #write(Transaction)}.
 *
 * <p>The runtime keeps the steps of every transaction while this exporter is configured ({@code
 * TraceRuntime}), so each line carries the steps whether or not the sampler captured the profile;
 * {@code profile_captured} says what the sampler decided (what the gRPC exporter would send). The
 * steps are those of the step builder ({@link ProfileSteps}): consecutive repeats folded into
 * {@code repeat_count}, {@code truncated}/{@code total_steps}. Profiles from the profile queue are
 * not written again ({@link #exportProfiles} keeps only a count).
 */
public final class JsonlFileExporter implements Exporter {
  private final File file;
  private final long maxBytes;
  private final Dictionary dictionary;
  private final String env;
  private final String version;
  private final OutputStream out;
  private long size;
  private long droppedForSize;
  private long profiles;

  JsonlFileExporter(File file, long maxBytes, Dictionary dictionary) throws IOException {
    this(file, maxBytes, dictionary, null, null);
  }

  /**
   * @param env {@code app.env} written on every line (null: left out)
   * @param version {@code app.version} written on every line (null: left out)
   */
  JsonlFileExporter(File file, long maxBytes, Dictionary dictionary, String env, String version)
      throws IOException {
    this.file = file.getAbsoluteFile();
    this.maxBytes = maxBytes;
    this.dictionary = dictionary;
    this.env = env;
    this.version = version;
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
              "JSONL exporter: {} reached its size cap ({} bytes); further transactions"
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

  /** The steps are on the transaction lines already; profiles are only counted. */
  @Override
  public void exportProfiles(List<ProfileRecord> batch) {
    profiles += batch.size();
  }

  /** Profiles received from the profile queue. */
  long profiles() {
    return profiles;
  }

  /** Transactions not written because the file reached its cap. */
  long droppedForSize() {
    return droppedForSize;
  }

  /**
   * The JSON line of {@code tx}. Transaction fields: {@code tx_id} (decimal string, 64-bit), {@code
   * trace_id}, {@code span_id}, {@code parent_span_id} (hex), {@code kind}, {@code env}, {@code
   * version} (instance values, left out when unset), {@code service}, {@code service_fixed}, {@code
   * start_epoch_ms}, {@code elapsed_ms}, {@code status}, {@code error}, {@code error_class}, {@code
   * error_message}, {@code http_method}, {@code path}, {@code remote_ip}, {@code user_id}, {@code
   * thread} (raw name), {@code thread_name} and {@code thread_name_id} (normalized, 10 §8), {@code
   * virtual_thread}, {@code flags} (names: {@code virtual_thread}, {@code leaked}, {@code
   * summarized}, {@code streaming}), {@code xlog_flags} (C-69 bits), {@code cpu_time_ms} and {@code
   * alloc_bytes} (left out when not measured, D-23), {@code profile_captured}, {@code truncated},
   * {@code total_steps}. When the steps were kept: {@code steps[]} ({@code seq}, {@code parent},
   * {@code type}, {@code name_id}, {@code name}, {@code target_id}, {@code target}, {@code
   * start_offset_ms}, {@code elapsed_ms}, {@code status}, {@code error}, {@code rows}, {@code
   * fetch_ms}, {@code detail}, {@code stack[]}, {@code thread}, {@code repeat_count}), {@code
   * summary[]} ({@code type}, {@code name}, {@code count}, {@code total_ms}, {@code max_ms}, {@code
   * errors}) and {@code summary_overflow}. Times are milliseconds with microsecond precision.
   */
  String write(Transaction tx) {
    return encode(tx, dictionary, env, version);
  }

  /** The JSON line of {@code tx} without instance fields (the encoding step of {@code noop}). */
  static String encode(Transaction tx, Dictionary dictionary) {
    return encode(tx, dictionary, null, null);
  }

  /** The JSON line of {@code tx} ({@link #write}). */
  static String encode(Transaction tx, Dictionary dictionary, String env, String version) {
    TraceContext ctx = tx.context();
    StringBuilder text = new StringBuilder(512);
    JsonWriter json = new JsonWriter(text).beginObject();
    json.field("tx_id", Long.toUnsignedString(ctx.txId))
        .field("trace_id", TraceIds.hex32(ctx.traceIdHi, ctx.traceIdLo))
        .field("span_id", TraceIds.hex16(ctx.spanId))
        .field("parent_span_id", ctx.parentSpanId == 0 ? null : TraceIds.hex16(ctx.parentSpanId))
        .field("kind", Kind.name(ctx.kind));
    if (env != null) {
      json.field("env", env);
    }
    if (version != null) {
      json.field("version", version);
    }
    json.field("service", ctx.serviceName)
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
        .field("thread_name", tx.threadNameNormalized())
        .field("thread_name_id", Long.toUnsignedString(tx.threadNameId()))
        .field("virtual_thread", ctx.virtualThread);
    int flags = tx.flags();
    json.beginArray("flags");
    if ((flags & TraceContext.FLAG_VIRTUAL_THREAD) != 0) {
      json.value("virtual_thread");
    }
    if ((flags & TraceContext.FLAG_LEAKED) != 0) {
      json.value("leaked");
    }
    if ((flags & TraceContext.FLAG_SUMMARIZED) != 0) {
      json.value("summarized");
    }
    if (ctx.streaming) {
      json.value("streaming");
    }
    json.endArray();
    json.field("xlog_flags", flags);
    if (tx.cpuNanos() >= 0) {
      json.millis("cpu_time_ms", tx.cpuNanos());
    }
    if (tx.allocatedBytes() >= 0) {
      json.field("alloc_bytes", tx.allocatedBytes());
    }
    json.field("profile_captured", tx.profileCaptured())
        .field("truncated", tx.truncated())
        .field("total_steps", tx.totalSteps());
    StepBuffer buffer = tx.steps();
    if (buffer != null) {
      steps(json, ProfileSteps.of(buffer), dictionary);
    }
    json.endObject();
    return text.toString();
  }

  /** {@code steps[]}, {@code summary[]} and {@code summary_overflow} of {@code profile}. */
  static void steps(JsonWriter json, ProfileSteps profile, Dictionary dictionary) {
    json.beginArray("steps");
    for (int seq = 0; seq < profile.size(); seq++) {
      long nameId = profile.nameId(seq);
      long targetId = profile.targetId(seq);
      json.beginObject()
          .field("seq", seq)
          .field("parent", profile.parent(seq))
          .field("type", StepType.name(profile.type(seq)))
          .field("name_id", Long.toUnsignedString(nameId))
          .field("name", dictionary.text(nameId))
          .field("target_id", Long.toUnsignedString(targetId))
          .field("target", dictionary.text(targetId))
          .millis("start_offset_ms", profile.startOffsetNanos(seq))
          .millis("elapsed_ms", profile.elapsedNanos(seq))
          .field("status", profile.status(seq))
          .field("error", profile.error(seq))
          .field("rows", profile.rows(seq))
          .millis("fetch_ms", profile.fetchNanos(seq))
          .field("detail", profile.detail(seq));
      long[] stack = profile.stack(seq);
      if (stack != null) {
        json.beginArray("stack");
        for (long frame : stack) {
          String name = dictionary.text(frame);
          json.value(name == null ? Long.toUnsignedString(frame) : name);
        }
        json.endArray();
      }
      json.field("thread", profile.threadName(seq))
          .field("repeat_count", profile.repeatCount(seq))
          .endObject();
    }
    json.endArray();
    json.beginArray("summary");
    for (int key = 0; key < profile.summarySize(); key++) {
      json.beginObject()
          .field("type", StepType.name(profile.summaryType(key)))
          .field("name", dictionary.text(profile.summaryNameId(key)))
          .field("count", profile.summaryCount(key))
          .millis("total_ms", profile.summaryTotalNanos(key))
          .millis("max_ms", profile.summaryMaxNanos(key))
          .field("errors", profile.summaryErrors(key))
          .endObject();
    }
    json.endArray();
    json.field("summary_overflow", profile.summaryOverflow());
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
