/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One line of the agent's JSONL exporter ({@code -Dargos.exporter=file}, field reference in {@code
 * JsonlFileExporter.write}): one transaction with its steps. Missing fields read as null (strings,
 * boxed numbers) so that tests can tell "absent" from zero (18 §5.5).
 *
 * @param fields the parsed JSON object
 * @param line the original line (assertion messages)
 */
public record TransactionRecord(Map<String, Object> fields, String line) {

  /** Parses one JSONL line. */
  @SuppressWarnings("unchecked")
  public static TransactionRecord parse(String line) {
    Object value = Json.parse(line);
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("not a JSON object: " + line);
    }
    return new TransactionRecord((Map<String, Object>) value, line);
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

  /** {@code streaming}, {@code timeout}, {@code leaked}, {@code summarized}. */
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
        steps.add(new StepRecord((Map<String, Object>) value));
      }
    }
    return steps;
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

  /** A field by its JSONL name (fields without an accessor). */
  public Object field(String name) {
    return fields.get(name);
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
  public record StepRecord(Map<String, Object> fields) {
    public Integer seq() {
      return integer("seq");
    }

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

    private Integer integer(String name) {
      return fields.get(name) instanceof Number n ? n.intValue() : null;
    }

    private String text(String name) {
      Object value = fields.get(name);
      return value == null ? null : String.valueOf(value);
    }
  }
}
