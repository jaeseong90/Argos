/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One collected transaction with its steps: a line of the agent's JSONL exporter ({@code
 * -Dargos.test.exporter=file:<dir>}, field reference in {@code JsonlFileExporter.write}) or a
 * {@code FakeCollector} record converted to the same field names. Missing fields read as null
 * (strings, boxed numbers) so that tests can tell "absent" from zero (18 §5.5); {@link #source()}
 * says which fields can exist at all.
 *
 * @param fields the parsed JSON object (or the converted record)
 * @param line the original line (assertion messages)
 * @param source where the record came from
 */
public record TransactionRecord(Map<String, Object> fields, String line, RecordSource source) {

  /** A JSONL record. */
  public TransactionRecord(Map<String, Object> fields, String line) {
    this(fields, line, RecordSource.JSONL);
  }

  /** Parses one JSONL line. */
  @SuppressWarnings("unchecked")
  public static TransactionRecord parse(String line) {
    Object value = Json.parse(line);
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("not a JSON object: " + line);
    }
    return new TransactionRecord((Map<String, Object>) value, line, RecordSource.JSONL);
  }

  public String txId() {
    return string("tx_id");
  }

  public String traceId() {
    return string("trace_id");
  }

  /** {@code HTTP}, {@code BATCH}, ... */
  public String kind() {
    return string("kind");
  }

  public String service() {
    return string("service");
  }

  public boolean serviceFixed() {
    return Boolean.TRUE.equals(fields.get("service_fixed"));
  }

  public Integer status() {
    Number value = number("status");
    return value == null ? null : value.intValue();
  }

  public boolean error() {
    return Boolean.TRUE.equals(fields.get("error"));
  }

  public String errorClass() {
    return string("error_class");
  }

  public String errorMessage() {
    return string("error_message");
  }

  /** Elapsed milliseconds (microsecond precision), null while absent. */
  public Double elapsedMs() {
    Number value = number("elapsed_ms");
    return value == null ? null : value.doubleValue();
  }

  public Long startEpochMs() {
    Number value = number("start_epoch_ms");
    return value == null ? null : value.longValue();
  }

  public String httpMethod() {
    return string("http_method");
  }

  public String path() {
    return string("path");
  }

  public String remoteIp() {
    return string("remote_ip");
  }

  public String userId() {
    return string("user_id");
  }

  public String thread() {
    return string("thread");
  }

  public boolean virtualThread() {
    return Boolean.TRUE.equals(fields.get("virtual_thread"));
  }

  /** The normalized thread name ({@code thread_name_id}, 10 §8), null while absent. */
  public String threadName() {
    return string("thread_name");
  }

  /** {@code app.env} of the instance, null while absent. */
  public String env() {
    return string("env");
  }

  /** {@code app.version} of the instance, null while absent. */
  public String version() {
    return string("version");
  }

  /** CPU milliseconds of the transaction's thread; null when not measured (D-23, not 0). */
  public Double cpuTimeMs() {
    Number value = number("cpu_time_ms");
    return value == null ? null : value.doubleValue();
  }

  /** Bytes allocated by the transaction's thread; null when not measured (D-23, not 0). */
  public Long allocBytes() {
    Number value = number("alloc_bytes");
    return value == null ? null : value.longValue();
  }

  /**
   * The C-69 {@code xlog.flags} bits (1 virtual thread, 2 leaked, 4 summarized), null if absent.
   */
  public Integer xlogFlags() {
    Number value = number("xlog_flags");
    return value == null ? null : value.intValue();
  }

  /** The sampler decision (10 §9.2 step 3), null while absent. */
  public Boolean profileCaptured() {
    Object value = fields.get("profile_captured");
    return value instanceof Boolean b ? b : null;
  }

  /** True when steps beyond {@code max-steps} were summarized (11 {@code truncated}). */
  public boolean truncated() {
    return Boolean.TRUE.equals(fields.get("truncated"));
  }

  /** Every step that started (11 {@code total_steps}), null while absent. */
  public Long totalSteps() {
    Number value = number("total_steps");
    return value == null ? null : value.longValue();
  }

  /** {@code virtual_thread}, {@code leaked}, {@code summarized}, {@code streaming}, ... */
  public List<String> flags() {
    List<String> flags = new ArrayList<>();
    if (fields.get("flags") instanceof List<?> values) {
      for (Object value : values) {
        flags.add(String.valueOf(value));
      }
    }
    return flags;
  }

  /** The steps in sequence order. */
  @SuppressWarnings("unchecked")
  public List<StepRecord> steps() {
    List<StepRecord> steps = new ArrayList<>();
    if (fields.get("steps") instanceof List<?> values) {
      for (Object value : values) {
        steps.add(new StepRecord((Map<String, Object>) value, source));
      }
    }
    return steps;
  }

  /** The summary-mode entries (10 §9.1 {@code SummaryStep}) in the order of the record. */
  @SuppressWarnings("unchecked")
  public List<SummaryRecord> summary() {
    List<SummaryRecord> entries = new ArrayList<>();
    if (fields.get("summary") instanceof List<?> values) {
      for (Object value : values) {
        entries.add(new SummaryRecord((Map<String, Object>) value));
      }
    }
    return entries;
  }

  /** The steps of a {@code StepType} name ({@code SQL}, {@code HTTP}, {@code ASYNC}, ...). */
  public List<StepRecord> steps(String type) {
    List<StepRecord> matching = new ArrayList<>();
    for (StepRecord step : steps()) {
      if (type.equals(step.type())) {
        matching.add(step);
      }
    }
    return matching;
  }

  /** A field by its name (fields without an accessor). */
  public Object field(String name) {
    return fields.get(name);
  }

  /** True when the field is present with a non-null value. */
  public boolean has(String name) {
    return fields.get(name) != null;
  }

  private String string(String name) {
    Object value = fields.get(name);
    return value == null ? null : String.valueOf(value);
  }

  private Number number(String name) {
    return fields.get(name) instanceof Number n ? n : null;
  }

  @Override
  public String toString() {
    return line;
  }

  /** One step of a transaction. */
  public record StepRecord(Map<String, Object> fields, RecordSource source) {
    public StepRecord(Map<String, Object> fields) {
      this(fields, RecordSource.JSONL);
    }

    public Integer seq() {
      return integer("seq");
    }

    /** Sequence number of the parent step, -1 or null at the top level. */
    public Integer parent() {
      return integer("parent");
    }

    /** {@code SQL}, {@code DBC}, {@code HTTP}, {@code ASYNC}, ... (C-1 names). */
    public String type() {
      return text("type");
    }

    public String name() {
      return text("name");
    }

    public String target() {
      return text("target");
    }

    public Double elapsedMs() {
      return fields.get("elapsed_ms") instanceof Number n ? n.doubleValue() : null;
    }

    public Double fetchMs() {
      return fields.get("fetch_ms") instanceof Number n ? n.doubleValue() : null;
    }

    public Integer status() {
      return integer("status");
    }

    public boolean error() {
      return Boolean.TRUE.equals(fields.get("error"));
    }

    public Long rows() {
      return fields.get("rows") instanceof Number n ? n.longValue() : null;
    }

    public String detail() {
      return text("detail");
    }

    /** The thread that recorded the step, when the source carries it. */
    public String thread() {
      return text("thread");
    }

    /** Consecutive identical steps folded into this one (11 {@code repeat_count}), or null. */
    public Integer repeatCount() {
      return integer("repeat_count");
    }

    /** A field by its name. */
    public Object field(String name) {
      return fields.get(name);
    }

    private Integer integer(String name) {
      return fields.get(name) instanceof Number n ? n.intValue() : null;
    }

    private String text(String name) {
      Object value = fields.get(name);
      return value == null ? null : String.valueOf(value);
    }

    @Override
    public String toString() {
      return fields.toString();
    }
  }

  /** One summary-mode entry: steps of one (type, name) aggregated beyond {@code max-steps}. */
  public record SummaryRecord(Map<String, Object> fields) {
    public String type() {
      Object value = fields.get("type");
      return value == null ? null : String.valueOf(value);
    }

    public String name() {
      Object value = fields.get("name");
      return value == null ? null : String.valueOf(value);
    }

    public Long count() {
      return fields.get("count") instanceof Number n ? n.longValue() : null;
    }

    public Double totalMs() {
      return fields.get("total_ms") instanceof Number n ? n.doubleValue() : null;
    }

    public Double maxMs() {
      return fields.get("max_ms") instanceof Number n ? n.doubleValue() : null;
    }

    public Long errors() {
      return fields.get("errors") instanceof Number n ? n.longValue() : null;
    }
  }
}
