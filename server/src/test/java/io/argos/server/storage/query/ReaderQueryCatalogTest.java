/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every SQL template the server ships ({@code sql/**}{@code /*.sql}) passes the rules of {@link
 * ReaderQuery} and renders with the default identifiers: a broken template fails here, before the
 * server start that would also refuse it.
 */
@Tag("req:SEC-76")
class ReaderQueryCatalogTest {

  @Test
  @DisplayName("T-ST-053a every shipped SQL template parses, renders and names rollups by alias")
  void shippedTemplates() {
    Map<String, ReaderQuery> templates =
        ReaderQueries.load(ReaderQueries.ROOT, ReaderQueryCatalogTest.class.getClassLoader());
    ReaderQueries queries = new ReaderQueries(templates, "argos", "", t -> t);
    for (ReaderQuery q : templates.values()) {
      if (q.segmentsPlaceholder()) {
        continue; // rendered with segments; the parse rules above already applied
      }
      String sql = queries.render(q);
      assertThat(sql).as(q.name()).doesNotContain("{db}").doesNotContain("{p}");
      assertThat(sql).as(q.name()).doesNotContain("{rollup:");
    }
    for (String name : templates.keySet()) {
      assertThat(name).as("template names are paths without .sql").doesNotEndWith(".sql");
    }
  }
}
