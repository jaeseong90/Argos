/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Dictionary;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 50 normalization cases of {@code normalization-cases.yaml} (10 §9.4 test list, 16 §6.5 PII
 * seeds): every case gives its fixed normalized text and {@code sql_id} through the normalizer and
 * through the SQL id cache, and no seed string survives (D-22, SEC-35).
 */
@Tag("req:D-22")
@Tag("req:SEC-35")
class NormalizationCasesTest {

  static List<NormalizationCases.Case> cases() throws IOException {
    return NormalizationCases.load();
  }

  @ParameterizedTest(name = "T-AG-034/T-ACC-22/T-SEC-35 case {0}")
  @MethodSource("cases")
  void normalizes(NormalizationCases.Case c) {
    String input = c.input();
    SqlNormalizer normalizer = c.normalizer();
    String expected = c.string("normalized");

    String normalized = normalizer.normalize(input, c.dialect());
    assertThat(normalized).as(c.toString()).isEqualTo(expected);
    assertThat(Dictionary.hash64(normalized)).as("sql_id of " + c).isEqualTo(c.sqlId());
    for (String seed : c.list("seeds")) {
      assertThat(normalized).as("seed of " + c).doesNotContain(seed);
    }
    assertThat(normalizer.truncated(input)).as("truncated " + c).isEqualTo(c.flag("truncated"));

    // The same statement through the id cache and the dictionary: the kept text is the normalized
    // one (pre-sent as SQL_TEXT with norm_version 1), never the raw input.
    AgentCounters counters = new AgentCounters();
    Dictionary dictionary = new Dictionary(counters);
    SqlIds ids = new SqlIds(dictionary, normalizer, counters);
    long id = ids.id(input, c.dialect());
    assertThat(id).isEqualTo(c.sqlId());
    assertThat(ids.id(input, c.dialect())).as("cached").isEqualTo(id);
    assertThat(dictionary.text(id)).isEqualTo(expected);
    assertThat(dictionary.kind(id)).isEqualTo(DictKind.SQL);
    assertThat(dictionary.truncated(id)).isEqualTo(c.flag("truncated"));
    if (!input.equals(expected)) {
      assertThat(dictionary.text(Dictionary.hash64(input))).as("raw text kept").isNull();
    }
    assertThat(counters.sqlUnparsed())
        .as("sql_unparsed_total")
        .isEqualTo(SqlNormalizer.UNPARSED.equals(expected) ? 1 : 0);
  }

  @Test
  @DisplayName(
      "T-AG-034 the fixture holds 50 cases (ids 1..50), 20 or more PII seed cases of 16 §6.5,"
          + " failure injection and the 64 KiB cut; whitespace variants share one sql_id")
  void fixtureCoversTheCatalogue() throws IOException {
    List<NormalizationCases.Case> cases = cases();
    assertThat(cases).hasSize(50);
    Set<Integer> ids = new HashSet<Integer>();
    int pii = 0;
    boolean injected = false;
    boolean truncated = false;
    Map<Integer, Long> sqlIds = new HashMap<Integer, Long>();
    for (NormalizationCases.Case c : cases) {
      assertThat(ids.add(c.id())).as("unique id " + c.id()).isTrue();
      sqlIds.put(c.id(), c.sqlId());
      if (c.flag("pii")) {
        pii++;
      }
      injected |= "budget".equals(c.string("inject"));
      truncated |= c.flag("truncated");
    }
    assertThat(ids).hasSize(50);
    assertThat(pii).isGreaterThanOrEqualTo(20);
    assertThat(injected).isTrue();
    assertThat(truncated).isTrue();
    int sameAs = 0;
    for (NormalizationCases.Case c : cases) {
      String other = c.string("same_as");
      if (other != null) {
        sameAs++;
        assertThat(c.sqlId()).as("case " + c.id()).isEqualTo(sqlIds.get(Integer.parseInt(other)));
      }
    }
    assertThat(sameAs).isPositive();
    // Statements that differ only in case are different statements (case is kept).
    assertThat(sqlIds.get(47)).isNotEqualTo(sqlIds.get(1));
  }
}
