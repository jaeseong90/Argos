/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import io.argos.storage.clickhouse.ClickHouseHttp;
import java.util.List;

/**
 * Applies the rendered schema with the migration account: lint first (nothing runs when a rule is
 * violated), then every statement in order, stopping at the first error. This is the ClickHouse
 * half of the migration runner of 13 §13.2 without the PostgreSQL history ({@code
 * ch_schema_history}, WO-20).
 */
public final class SchemaApplier {

  private SchemaApplier() {}

  /** Applies every migration of the repository with the given parameters. */
  public static List<String> apply(ClickHouseHttp migrator, SchemaParameters params) {
    return apply(migrator, SchemaRenderer.fromRepository().renderAll(params));
  }

  /** Lints and applies already rendered statements; returns them. */
  public static List<String> apply(ClickHouseHttp migrator, List<String> statements) {
    List<String> violations = SchemaLint.violations(statements);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException("Schema lint failed:\n" + String.join("\n", violations));
    }
    for (String statement : statements) {
      migrator.execute(statement);
    }
    return statements;
  }
}
