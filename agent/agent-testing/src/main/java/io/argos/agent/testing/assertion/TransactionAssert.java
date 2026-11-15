/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on one collected transaction (18 §5.5):
 *
 * <pre>
 * assertThatTransaction(tx)
 *     .hasKind("HTTP").hasService("GET /api/orders/{id}").hasStatus(200).isNotError()
 *     .cpuTimeMs().isMissing()                  // virtual thread: missing, not 0 (D-23)
 *     .steps().ofType("SQL").hasSize(1)
 *     .transaction().noStepFromThread(startsWith("argos-"));
 * </pre>
 *
 * Fields the record's source cannot carry (on the JSONL exporter: connection wait, SQL ids; on the
 * collector view: step threads) fail as {@link Unsupported} instead of passing.
 */
public final class TransactionAssert extends AbstractAssert<TransactionAssert, TransactionRecord> {

  TransactionAssert(TransactionRecord actual) {
    super(actual, TransactionAssert.class);
  }

  public static TransactionAssert assertThatTransaction(TransactionRecord actual) {
    return new TransactionAssert(actual);
  }

  /** {@code HTTP}, {@code BATCH}, {@code MESSAGE}. */
  public TransactionAssert hasKind(String kind) {
    isNotNull();
    if (!kind.equals(actual.kind())) {
      failWithMessage("expected kind <%s> but was <%s> in%n%s", kind, actual.kind(), actual);
    }
    return this;
  }

  public TransactionAssert hasService(String service) {
    isNotNull();
    if (!service.equals(actual.service())) {
      failWithMessage(
          "expected service <%s> but was <%s> in%n%s", service, actual.service(), actual);
    }
    return this;
  }

  public TransactionAssert hasStatus(int status) {
    isNotNull();
    if (actual.status() == null || actual.status() != status) {
      failWithMessage("expected status <%s> but was <%s> in%n%s", status, actual.status(), actual);
    }
    return this;
  }

  public TransactionAssert isError() {
    isNotNull();
    if (!actual.error()) {
      failWithMessage("expected an error transaction:%n%s", actual);
    }
    return this;
  }

  public TransactionAssert isNotError() {
    isNotNull();
    if (actual.error()) {
      failWithMessage("expected no error:%n%s", actual);
    }
    return this;
  }

  public TransactionAssert hasHttpMethod(String method) {
    isNotNull();
    if (!method.equals(actual.httpMethod())) {
      failWithMessage(
          "expected http_method <%s> but was <%s> in%n%s", method, actual.httpMethod(), actual);
    }
    return this;
  }

  public TransactionAssert hasPath(String path) {
    isNotNull();
    if (!path.equals(actual.path())) {
      failWithMessage("expected path <%s> but was <%s> in%n%s", path, actual.path(), actual);
    }
    return this;
  }

  /** {@code app.env} of the transaction's instance (F-A21/A25). */
  public TransactionAssert hasEnv(String env) {
    return field("env").isEqualTo(env);
  }

  /** {@code app.version} of the transaction's instance (F-A21). */
  public TransactionAssert hasVersion(String version) {
    return field("version").isEqualTo(version);
  }

  /** The C-69 flag name ({@code virtual_thread}, {@code leaked}, {@code summarized}) is set. */
  public TransactionAssert hasFlag(String flag) {
    field("flags");
    if (!actual.flags().contains(flag)) {
      failWithMessage(
          "expected flag <%s> but the flags were %s in%n%s", flag, actual.flags(), actual);
    }
    return this;
  }

  /** The flag name is not set. */
  public TransactionAssert doesNotHaveFlag(String flag) {
    field("flags");
    if (actual.flags().contains(flag)) {
      failWithMessage("expected no flag <%s> in%n%s", flag, actual);
    }
    return this;
  }

  /** The sampler chose to send the profile (10 §9.2 step 3, {@code profile_captured}). */
  public TransactionAssert isProfileCaptured() {
    field("profile_captured");
    if (!Boolean.TRUE.equals(actual.profileCaptured())) {
      failWithMessage("expected profile_captured=true in%n%s", actual);
    }
    return this;
  }

  /** The sampler did not send the profile. */
  public TransactionAssert isNotProfileCaptured() {
    field("profile_captured");
    if (!Boolean.FALSE.equals(actual.profileCaptured())) {
      failWithMessage("expected profile_captured=false in%n%s", actual);
    }
    return this;
  }

  /** Elapsed time present and not negative (D-01). */
  public TransactionAssert hasElapsed() {
    isNotNull();
    Double elapsed = actual.elapsedMs();
    if (elapsed == null || elapsed < 0) {
      failWithMessage("expected elapsed_ms >= 0 but was <%s> in%n%s", elapsed, actual);
    }
    return this;
  }

  public TransactionAssert hasElapsedAtLeast(double millis) {
    hasElapsed();
    if (actual.elapsedMs() < millis) {
      failWithMessage(
          "expected elapsed_ms >= %s but was <%s> in%n%s", millis, actual.elapsedMs(), actual);
    }
    return this;
  }

  /** Exactly {@code count} steps of {@code type} ({@code SQL}, {@code HTTP}, ...). */
  public TransactionAssert hasStepCount(String type, int count) {
    isNotNull();
    int steps = actual.steps(type).size();
    if (steps != count) {
      failWithMessage("expected %s %s step(s) but found %s in%n%s", count, type, steps, actual);
    }
    return this;
  }

  /** The elapsed time ({@code elapsed_ms}). */
  public ValueAssert<TransactionAssert> elapsedMs() {
    return field("elapsed_ms");
  }

  /** CPU time; missing (not 0) when the agent cannot measure it (virtual threads, D-23). */
  public ValueAssert<TransactionAssert> cpuTimeMs() {
    return field("cpu_time_ms");
  }

  /** Allocated bytes; missing (not 0) when the agent cannot measure it (D-23). */
  public ValueAssert<TransactionAssert> allocBytes() {
    return field("alloc_bytes");
  }

  /** Every step that started (11 {@code total_steps}), including summarized ones. */
  public ValueAssert<TransactionAssert> totalSteps() {
    return field("total_steps");
  }

  /** {@code truncated} (11): steps beyond {@code max-steps} were summarized. */
  public ValueAssert<TransactionAssert> truncated() {
    return field("truncated");
  }

  /** The normalized thread name behind {@code thread_name_id} (10 §8 {@code ThreadNames}). */
  public ValueAssert<TransactionAssert> threadName() {
    return field("thread_name");
  }

  /** Any field by its record name; unsupported fields fail. */
  public ValueAssert<TransactionAssert> field(String name) {
    isNotNull();
    if (!actual.source().supportsTransactionField(name)) {
      throw Unsupported.error(
          "transaction field '" + name + "'", actual.source().name(), arrivesWith(name));
    }
    return new ValueAssert<>(this, name, actual.field(name), actual.toString());
  }

  /** The transaction's steps. */
  public StepsAssert steps() {
    isNotNull();
    return new StepsAssert(this, actual.steps());
  }

  /**
   * The transaction ran on a thread that {@code thread} rejects (e.g. not {@code argos-*}). A
   * transaction without a thread name fails ("thread unknown"): the check needs the value.
   */
  public TransactionAssert isNotFromThread(Predicate<String> thread) {
    field("thread");
    if (actual.thread() == null || actual.thread().isEmpty()) {
      failWithMessage(
          "thread unknown: cannot check that the transaction is not from thread %s:%n%s",
          thread, actual);
    }
    if (thread.test(actual.thread())) {
      failWithMessage(
          "expected the transaction not to come from thread %s but it ran on <%s>:%n%s",
          thread, actual.thread(), actual);
    }
    return this;
  }

  /**
   * No step was recorded by a thread that {@code thread} accepts (18 §5.8 re-entrance: {@code
   * startsWith("argos-")}). Needs the step's thread in the source; the JSONL exporter has none.
   */
  public TransactionAssert noStepFromThread(Predicate<String> thread) {
    isNotNull();
    if (!actual.source().supportsStepField("thread")) {
      throw Unsupported.error(
          "the thread of a step", actual.source().name(), "WO-10/WO-11 (step thread)");
    }
    for (StepRecord step : actual.steps()) {
      if (step.thread() != null && thread.test(step.thread())) {
        failWithMessage(
            "expected no step from thread %s but found %s in%n%s", thread, step, actual);
      }
    }
    return this;
  }

  /**
   * The step tree, one line per step in sequence order: two spaces per depth, then {@code <type>
   * <name>} ({@code SQL select ...}).
   */
  public static List<String> stepTree(TransactionRecord tx) {
    List<StepRecord> steps = tx.steps();
    List<String> lines = new ArrayList<>();
    for (StepRecord step : steps) {
      int depth = 0;
      Integer parent = step.parent();
      int guard = 0;
      while (parent != null && parent >= 0 && guard++ < steps.size()) {
        depth++;
        parent = parentOf(steps, parent);
      }
      lines.add("  ".repeat(depth) + step.type() + " " + step.name());
    }
    return lines;
  }

  private static Integer parentOf(List<StepRecord> steps, int seq) {
    for (StepRecord step : steps) {
      if (step.seq() != null && step.seq() == seq) {
        return step.parent();
      }
    }
    return null;
  }

  /** The step tree ({@link #stepTree(TransactionRecord)}) is exactly {@code lines}. */
  public TransactionAssert hasStepTree(String... lines) {
    isNotNull();
    List<String> tree = stepTree(actual);
    if (!tree.equals(List.of(lines))) {
      failWithMessage(
          "expected step tree%n%s%nbut was%n%s%nin%n%s",
          String.join("\n", lines), String.join("\n", tree), actual);
    }
    return this;
  }

  /** The transaction under test. */
  public TransactionRecord actualRecord() {
    return actual;
  }

  static String arrivesWith(String field) {
    return switch (field) {
      case "env", "version" -> "the gRPC exporter of WO-11 (instance metadata)";
      case "cpu_time_ms", "alloc_bytes" -> "the gRPC exporter of WO-11";
      case "headers", "request_id", "dispatch_count" -> "WO-12 (servlet) and WO-11";
      case "dbc_wait_ms", "sql_ids", "batch_size" -> "WO-13 (jdbc) and WO-11";
      default -> "the gRPC exporter of WO-11";
    };
  }
}
