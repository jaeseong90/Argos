/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The agent's own counters (10 §11 "에이전트 자기" rows, 18 §19, D-06, D-19, D-20): one {@link LongAdder}
 * per counter and label value, so application threads add without contention and never block
 * (N-05). Every label set is fixed and bounded; a label value outside the set counts as {@value
 * #OTHER}. One instance per agent, shared by the tracer, the queues, the transform listener and the
 * self-diagnostics.
 *
 * <p>Counters ({@link #snapshot()} name, label values):
 *
 * <ul>
 *   <li>{@code argos_agent_dropped_total{queue}}: {@link #QUEUES}
 *   <li>{@code argos_agent_ignored_total{reason}}: {@link #IGNORE_REASONS} (10 §7.2)
 *   <li>{@code argos_agent_orphan_steps_total{type}}: step type names of C-1, lower case
 *   <li>{@code argos_agent_module_errors_total{module}}: module ids set by {@link
 *       #setModuleNames(String[])}
 *   <li>{@code argos_agent_transform_errors_total{reason}}: {@link #TRANSFORM_ERROR_REASONS} (10
 *       §14: {@code circularity} for {@code ClassCircularityError}, {@code error} otherwise)
 *   <li>{@code argos_agent_sql_outside_tx_total} (10 §6.8, DSN-10-07)
 *   <li>{@code argos_agent_transactions_leaked_total}, {@code
 *       argos_agent_transactions_unanchored_total} (10 §19 to 12)
 *   <li>{@code argos_agent_dictionary_overflow_total{kind}}: dictionary kind names of C-16, lower
 *       case
 *   <li>{@code argos_agent_service_dictionary_overflow_total} (F-A29 {@code _other_} services)
 *   <li>{@code argos_agent_sql_unparsed_total} (10 §9.4)
 *   <li>{@code argos_agent_profile_dropped_total} (10 §9.2 step 3)
 *   <li>{@code argos_agent_scope_chain_cut_total}: activations that found 64 unclosed bindings on
 *       their thread and started a new chain (10 §14 "상한", {@code TracerImpl.activate})
 *   <li>{@code argos_agent_user_id_skipped_total{reason}}: {@link #USER_ID_SKIPPED_REASONS},
 *       transactions whose user id was not recorded ({@code hashed} without salt; an {@code mdc:}
 *       source whose key no log event of the transaction carried; ADR-0010 C-125)
 * </ul>
 */
public final class AgentCounters {
  /** Label value of anything outside a fixed label set. */
  public static final String OTHER = "_other_";

  /** Queue labels (10 §10.1). */
  public static final List<String> QUEUES =
      Collections.unmodifiableList(
          Arrays.asList("transaction", "profile", "stats", "metric", "active"));

  /** Exclusion reasons of {@code Tracer.ignoreReason} (10 §7.2). */
  public static final List<String> IGNORE_REASONS =
      Collections.unmodifiableList(Arrays.asList("method", "path", "extension", "user_agent"));

  /** Transformation error reasons (10 §14 {@code transform_errors_total{reason}}). */
  public static final List<String> TRANSFORM_ERROR_REASONS =
      Collections.unmodifiableList(Arrays.asList("error", "circularity"));

  /** {@code user_id_skipped_total{reason}}: {@code hashed} without a salt (ADR-0010 C-125). */
  public static final String USER_ID_SKIPPED_NO_SALT = "no_salt";

  /**
   * {@code user_id_skipped_total{reason}}: a transaction ended without a value of the {@code
   * mdc:<key>} source (no log event of the transaction carried the key; ADR-0010 C-125 (3), WO-18).
   */
  public static final String USER_ID_SKIPPED_MDC_ABSENT = "mdc_absent";

  /** Reasons of {@code argos_agent_user_id_skipped_total} (ADR-0010 C-125). */
  public static final List<String> USER_ID_SKIPPED_REASONS =
      Collections.unmodifiableList(
          Arrays.asList(USER_ID_SKIPPED_NO_SALT, USER_ID_SKIPPED_MDC_ABSENT));

  static final String DROPPED = "argos_agent_dropped_total";
  static final String IGNORED = "argos_agent_ignored_total";
  static final String ORPHAN_STEPS = "argos_agent_orphan_steps_total";
  static final String MODULE_ERRORS = "argos_agent_module_errors_total";
  static final String TRANSFORM_ERRORS = "argos_agent_transform_errors_total";
  static final String SQL_OUTSIDE_TX = "argos_agent_sql_outside_tx_total";
  static final String TRANSACTIONS_LEAKED = "argos_agent_transactions_leaked_total";
  static final String TRANSACTIONS_UNANCHORED = "argos_agent_transactions_unanchored_total";
  static final String DICTIONARY_OVERFLOW = "argos_agent_dictionary_overflow_total";
  static final String SERVICE_DICTIONARY_OVERFLOW = "argos_agent_service_dictionary_overflow_total";
  static final String SQL_UNPARSED = "argos_agent_sql_unparsed_total";
  static final String PROFILE_DROPPED = "argos_agent_profile_dropped_total";
  static final String SCOPE_CHAIN_CUT = "argos_agent_scope_chain_cut_total";
  static final String USER_ID_SKIPPED = "argos_agent_user_id_skipped_total";

  /** Self metric of {@link #stackCaptureSeconds()} (10 §11 "에이전트 자기", §13.1). */
  public static final String STACK_CAPTURE_SECONDS = "argos_agent_stack_capture_seconds_total";

  /** Step types that can be orphans: SQL (1) .. MESSAGE (10), index = type. */
  private static final int STEP_TYPES = StepType.MESSAGE + 1;

  private final LongAdder[] dropped = adders(QUEUES.size() + 1);
  private final LongAdder[] ignored = adders(IGNORE_REASONS.size() + 1);
  private final LongAdder[] orphans = adders(STEP_TYPES + 1);
  private final LongAdder[] moduleErrors = adders(ModuleHealth.MAX_MODULES + 1);
  private final LongAdder[] dictionaryOverflow = adders(DictKind.COUNT + 1);
  private final LongAdder[] transformErrors = adders(TRANSFORM_ERROR_REASONS.size() + 1);
  private final LongAdder sqlOutsideTx = new LongAdder();
  private final LongAdder transactionsLeaked = new LongAdder();
  private final LongAdder transactionsUnanchored = new LongAdder();
  private final LongAdder serviceDictionaryOverflow = new LongAdder();
  private final LongAdder sqlUnparsed = new LongAdder();
  private final LongAdder profileDropped = new LongAdder();
  private final LongAdder scopeChainCut = new LongAdder();
  private final LongAdder[] userIdSkipped = adders(USER_ID_SKIPPED_REASONS.size() + 1);
  private final LongAdder stackCaptureNanos = new LongAdder();
  private final LongAdder stackCaptures = new LongAdder();

  /** Module id to module name ({@link #setModuleNames}); null slots are not reported. */
  private final AtomicReferenceArray<String> moduleNames =
      new AtomicReferenceArray<String>(ModuleHealth.MAX_MODULES);

  private static LongAdder[] adders(int count) {
    LongAdder[] adders = new LongAdder[count];
    for (int i = 0; i < count; i++) {
      adders[i] = new LongAdder();
    }
    return adders;
  }

  /**
   * Names the modules by id (the {@code module} label values): {@code names[id]} is the module id
   * string of 10 §5.1, null for unused ids. At most {@link ModuleHealth#MAX_MODULES} entries.
   */
  public void setModuleNames(String[] names) {
    for (int i = 0; i < ModuleHealth.MAX_MODULES; i++) {
      moduleNames.set(i, names != null && i < names.length ? names[i] : null);
    }
  }

  // --- increments (application threads: LongAdder only, never blocking) ------------------------

  /** One item dropped from {@code queue} ({@link #QUEUES}). */
  public void incDropped(String queue) {
    dropped[index(QUEUES, queue)].increment();
  }

  /** {@code count} items dropped from {@code queue} (a whole batch, 11 §6). */
  public void addDropped(String queue, long count) {
    if (count > 0) {
      dropped[index(QUEUES, queue)].add(count);
    }
  }

  /** One request excluded for {@code reason} ({@link #IGNORE_REASONS}). */
  public void incIgnored(String reason) {
    ignored[index(IGNORE_REASONS, reason)].increment();
  }

  /** One step of {@link StepType} {@code stepType} seen without a transaction (D-20). */
  public void incOrphanStep(int stepType) {
    orphans[stepType >= StepType.SQL && stepType < STEP_TYPES ? stepType : STEP_TYPES].increment();
  }

  /** One advice or helper error of module {@code moduleId} (10 §5.2). */
  public void incModuleError(int moduleId) {
    moduleErrors[validModule(moduleId) ? moduleId : ModuleHealth.MAX_MODULES].increment();
  }

  /** One transformation error ({@code TransformErrorListener}, 10 §14). */
  public void incTransformError() {
    incTransformError("error");
  }

  /**
   * One transformation error for {@code reason} ({@link #TRANSFORM_ERROR_REASONS}: {@code
   * circularity} for a {@code ClassCircularityError}, {@code error} otherwise).
   */
  public void incTransformError(String reason) {
    transformErrors[index(TRANSFORM_ERROR_REASONS, reason)].increment();
  }

  /** Transformation errors of {@code reason}. */
  public long transformErrors(String reason) {
    return transformErrors[index(TRANSFORM_ERROR_REASONS, reason)].sum();
  }

  /** One SQL execution outside a transaction (10 §6.8, DSN-10-07). */
  public void incSqlOutsideTx() {
    sqlOutsideTx.increment();
  }

  /** One transaction ended by the agent because its context leaked (C-69 {@code leaked}). */
  public void incTransactionLeaked() {
    transactionsLeaked.increment();
  }

  /** One transaction that ended without its request anchor (10 §7.1, DSN-10-02). */
  public void incTransactionUnanchored() {
    transactionsUnanchored.increment();
  }

  /** One dictionary text not kept because {@link DictKind} {@code kind} was full. */
  public void incDictionaryOverflow(int kind) {
    dictionaryOverflow[kind >= DictKind.SERVICE && kind < DictKind.COUNT ? kind : DictKind.COUNT]
        .increment();
  }

  /** One service name mapped to {@code _other_} (F-A29). */
  public void incServiceDictionaryOverflow() {
    serviceDictionaryOverflow.increment();
  }

  /** One SQL text that could not be normalized ({@code <unparsed>}, 10 §9.4). */
  public void incSqlUnparsed() {
    sqlUnparsed.increment();
  }

  /** One profile dropped although {@code profile_captured} was set (10 §9.2 step 3). */
  public void incProfileDropped() {
    profileDropped.increment();
  }

  /** One binding chain cut at its bound ({@code TracerImpl.activate}, 10 §14). */
  public void incScopeChainCut() {
    scopeChainCut.increment();
  }

  /**
   * One transaction whose user id was not recorded for {@code reason} ({@link
   * #USER_ID_SKIPPED_REASONS}, ADR-0010 C-125).
   */
  public void incUserIdSkipped(String reason) {
    userIdSkipped[index(USER_ID_SKIPPED_REASONS, reason)].increment();
  }

  public long userIdSkipped(String reason) {
    return userIdSkipped[index(USER_ID_SKIPPED_REASONS, reason)].sum();
  }

  /**
   * One slow-stack cycle of {@code argos-watch} (10 §13.1, WO-19): {@code stacks} stacks taken in
   * {@code nanos} nanoseconds (the batched {@code getThreadInfo}, the virtual-thread stacks and the
   * STACK steps written). Kept apart from {@link #snapshot()} (a long map): {@link
   * #stackCaptureSeconds()} is {@value #STACK_CAPTURE_SECONDS} in the self metrics and {@code
   * stack_capture_seconds_total} in the self-diagnostics.
   */
  public void addStackCapture(long nanos, int stacks) {
    if (nanos > 0) {
      stackCaptureNanos.add(nanos);
    }
    if (stacks > 0) {
      stackCaptures.add(stacks);
    }
  }

  /** Seconds spent taking slow stacks since start ({@value #STACK_CAPTURE_SECONDS}). */
  public double stackCaptureSeconds() {
    return stackCaptureNanos.sum() / 1e9;
  }

  /** Slow stacks taken since start. */
  public long stackCaptures() {
    return stackCaptures.sum();
  }

  // --- reads ----------------------------------------------------------------------------------

  public long dropped(String queue) {
    return dropped[index(QUEUES, queue)].sum();
  }

  /** Dropped items of every queue together. */
  public long droppedTotal() {
    return sum(dropped);
  }

  public long ignored(String reason) {
    return ignored[index(IGNORE_REASONS, reason)].sum();
  }

  public long orphanSteps(int stepType) {
    return orphans[stepType >= StepType.SQL && stepType < STEP_TYPES ? stepType : STEP_TYPES].sum();
  }

  /** Orphan steps of every type together (18 §19 {@code orphan_steps_total}). */
  public long orphanStepsTotal() {
    return sum(orphans);
  }

  public long moduleErrors(int moduleId) {
    return moduleErrors[validModule(moduleId) ? moduleId : ModuleHealth.MAX_MODULES].sum();
  }

  public long transformErrors() {
    return sum(transformErrors);
  }

  public long sqlOutsideTx() {
    return sqlOutsideTx.sum();
  }

  public long transactionsLeaked() {
    return transactionsLeaked.sum();
  }

  public long transactionsUnanchored() {
    return transactionsUnanchored.sum();
  }

  public long dictionaryOverflow(int kind) {
    return dictionaryOverflow[
        kind >= DictKind.SERVICE && kind < DictKind.COUNT ? kind : DictKind.COUNT]
        .sum();
  }

  public long serviceDictionaryOverflow() {
    return serviceDictionaryOverflow.sum();
  }

  public long sqlUnparsed() {
    return sqlUnparsed.sum();
  }

  public long profileDropped() {
    return profileDropped.sum();
  }

  public long scopeChainCut() {
    return scopeChainCut.sum();
  }

  /**
   * Every counter with its OpenMetrics name and labels, in a fixed order, zeros included: {@code
   * argos_agent_dropped_total{queue="transaction"}} ... The {@code _other_} label appears only when
   * it counted something; module labels for named modules, errors of unnamed or invalid module ids
   * under {@code _other_}. Bounded: at most 113 entries.
   */
  public Map<String, Long> snapshot() {
    Map<String, Long> out = new LinkedHashMap<String, Long>();
    labelled(out, DROPPED, "queue", QUEUES, dropped);
    labelled(out, IGNORED, "reason", IGNORE_REASONS, ignored);
    for (int type = StepType.SQL; type < STEP_TYPES; type++) {
      out.put(name(ORPHAN_STEPS, "type", lower(StepType.name(type))), orphans[type].sum());
    }
    other(out, ORPHAN_STEPS, "type", orphans[STEP_TYPES]);
    long unnamedModules = moduleErrors[ModuleHealth.MAX_MODULES].sum();
    for (int id = 0; id < ModuleHealth.MAX_MODULES; id++) {
      String module = moduleNames.get(id);
      long value = moduleErrors[id].sum();
      if (module != null) {
        out.put(name(MODULE_ERRORS, "module", module), value);
      } else {
        unnamedModules += value;
      }
    }
    if (unnamedModules > 0) {
      out.put(name(MODULE_ERRORS, "module", OTHER), unnamedModules);
    }
    labelled(out, TRANSFORM_ERRORS, "reason", TRANSFORM_ERROR_REASONS, transformErrors);
    out.put(SQL_OUTSIDE_TX, sqlOutsideTx.sum());
    out.put(TRANSACTIONS_LEAKED, transactionsLeaked.sum());
    out.put(TRANSACTIONS_UNANCHORED, transactionsUnanchored.sum());
    for (int kind = DictKind.SERVICE; kind < DictKind.COUNT; kind++) {
      out.put(
          name(DICTIONARY_OVERFLOW, "kind", lower(DictKind.name(kind))),
          dictionaryOverflow[kind].sum());
    }
    other(out, DICTIONARY_OVERFLOW, "kind", dictionaryOverflow[DictKind.COUNT]);
    out.put(SERVICE_DICTIONARY_OVERFLOW, serviceDictionaryOverflow.sum());
    out.put(SQL_UNPARSED, sqlUnparsed.sum());
    out.put(PROFILE_DROPPED, profileDropped.sum());
    out.put(SCOPE_CHAIN_CUT, scopeChainCut.sum());
    labelled(out, USER_ID_SKIPPED, "reason", USER_ID_SKIPPED_REASONS, userIdSkipped);
    return Collections.unmodifiableMap(out);
  }

  /** {@code metric{label="value"}}. */
  static String name(String metric, String label, String value) {
    return metric + "{" + label + "=\"" + value + "\"}";
  }

  private static void labelled(
      Map<String, Long> out, String metric, String label, List<String> values, LongAdder[] adders) {
    for (int i = 0; i < values.size(); i++) {
      out.put(name(metric, label, values.get(i)), adders[i].sum());
    }
    other(out, metric, label, adders[values.size()]);
  }

  private static void other(Map<String, Long> out, String metric, String label, LongAdder adder) {
    long value = adder.sum();
    if (value > 0) {
      out.put(name(metric, label, OTHER), value);
    }
  }

  private static int index(List<String> values, String value) {
    int i = value == null ? -1 : values.indexOf(value);
    return i < 0 ? values.size() : i;
  }

  private static boolean validModule(int moduleId) {
    return moduleId >= 0 && moduleId < ModuleHealth.MAX_MODULES;
  }

  private static long sum(LongAdder[] adders) {
    long total = 0;
    for (LongAdder adder : adders) {
      total += adder.sum();
    }
    return total;
  }

  private static String lower(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
