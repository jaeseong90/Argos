/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

/**
 * Receives enum values the collector does not know or that are UNSPECIFIED where a value is
 * required (11 §4.4: counted in {@code argos_collector_unknown_enum_total{field}}, never a reason
 * to reject; ADR-0010 C-143 (1): WARN at most once a minute).
 */
@FunctionalInterface
public interface UnknownEnumSink {

  /** {@code ip_mode} of {@code AgentHello}. */
  String IP_MODE = "ip_mode";

  /** {@code user_id_mode} of {@code AgentHello}. */
  String USER_ID_MODE = "user_id_mode";

  /** {@code query_string_mode} of {@code AgentHello}. */
  String QUERY_STRING_MODE = "query_string_mode";

  /** {@code sql_param_mode} of {@code AgentHello}. */
  String SQL_PARAM_MODE = "sql_param_mode";

  /** {@code TransactionRecord.kind}. */
  String TRANSACTION_KIND = "transaction.kind";

  /** {@code TransactionProfile.kind}. */
  String PROFILE_KIND = "profile.kind";

  /** {@code ProfileStep.type}. */
  String STEP_TYPE = "step.type";

  /** {@code MetricSeries.kind}. */
  String METRIC_KIND = "metric.kind";

  /** {@code SqlStat.kind}. */
  String SQL_STAT_KIND = "sql_stat.kind";

  /** {@code ExtStat.kind}. */
  String EXT_STAT_KIND = "ext_stat.kind";

  /** {@code DictionaryEntry.kind}. */
  String DICTIONARY_KIND = "dictionary.kind";

  /** {@code count} values of {@code field} (one of the constants of this interface). */
  void unknownEnum(String field, int count);
}
