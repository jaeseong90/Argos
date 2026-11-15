/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.StepType;
import java.util.Optional;

/**
 * Protocol dictionary kinds (C-16, ADR-0010 C-101) and the PostgreSQL {@code dict_kind} names of
 * V001 plus {@code pool} ({@code V003__ingest.sql}), and which kind each id field of a row refers
 * to.
 */
public final class DictKinds {

  private DictKinds() {}

  /** The {@code dict_kind} name of a protocol kind; empty for UNSPECIFIED or unknown values. */
  public static Optional<String> pgName(DictionaryKind kind) {
    return Optional.ofNullable(
        switch (kind) {
          case SERVICE -> "service";
          case SQL_TEXT -> "sql";
          case CLASS -> "class";
          case METHOD_NAME -> "method";
          case THREAD -> "thread";
          case HOST -> "host";
          case FRAME -> "frame";
          case METRIC -> "metric";
          case ERROR_GROUP -> "error_group";
          case DB_TARGET -> "db_target";
          case APP_VERSION -> "app_version";
          case POOL -> "pool";
          case DICTIONARY_KIND_UNSPECIFIED, UNRECOGNIZED -> null;
        });
  }

  /**
   * The kind of {@code ProfileStep.name_id} for a step of {@code type} (11 §4.3 field comment, 10
   * §6.3, ADR-0010 C-143 (4)); empty when the step type names nothing in a dictionary.
   */
  public static Optional<DictionaryKind> stepName(StepType type) {
    return Optional.ofNullable(
        switch (type) {
          case SQL -> DictionaryKind.SQL_TEXT;
          case DBC -> DictionaryKind.POOL;
          case HTTP_CALL -> DictionaryKind.HOST;
          case METHOD, ASYNC -> DictionaryKind.METHOD_NAME;
          case ERROR -> DictionaryKind.CLASS;
          default -> null;
        });
  }

  /**
   * The kind of {@code ProfileStep.target_id} for a step of {@code type}: SQL, DBC, COMMIT and
   * ROLLBACK name a DB_TARGET, HTTP calls a HOST.
   */
  public static Optional<DictionaryKind> stepTarget(StepType type) {
    return Optional.ofNullable(
        switch (type) {
          case SQL, DBC, COMMIT, ROLLBACK -> DictionaryKind.DB_TARGET;
          case HTTP_CALL -> DictionaryKind.HOST;
          default -> null;
        });
  }
}
