/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.catalog.BatchCatalog;
import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.Catalogs;
import io.argos.sample.common.catalog.Check;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.seed.Seeds;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Consistency of the shared catalogs and the seed values (18 §4.2, §4.3). */
class CatalogAndSeedsTest {

  @Test
  @DisplayName("Every 18 §4.2 route of the shop catalog has an entry")
  void shopCatalogCoversSection42() {
    Set<String> routes =
        ShopCatalog.entries().stream()
            .map(e -> e.method() + " " + e.route())
            .collect(Collectors.toSet());
    assertThat(routes)
        .contains(
            "GET /api/orders/{id}",
            "GET /api/orders",
            "GET /api/orders/{id}/items",
            "GET /api/pay/{id}",
            "GET /api/slow",
            "GET /api/error-500",
            "GET /api/send-error",
            "GET /api/forward",
            "GET /api/not-found-x",
            "GET /api/async/deferred",
            "GET /api/async/callable",
            "GET /api/async/executor",
            "GET /api/sse",
            "GET /ws/echo",
            "GET /api/pool/exhaust",
            "POST /api/tx/commit",
            "POST /api/tx/rollback",
            "GET /api/stmt",
            "GET /api/batch-insert",
            "GET /api/user",
            "GET /actuator/health",
            "GET /favicon.ico",
            "GET /static/app.js",
            "OPTIONS /api/orders",
            "GET /api/threads/dump-me",
            "GET /api/mdc-log",
            "GET /api/version");
  }

  @Test
  @DisplayName("Entry ids are unique and paths are valid request targets")
  void idsAndPaths() {
    for (List<CatalogEntry> catalog :
        Arrays.asList(Catalogs.byName("shop"), BatchCatalog.entries())) {
      Set<String> ids = new HashSet<>();
      for (CatalogEntry entry : catalog) {
        assertThat(ids.add(entry.id())).as("duplicate id %s", entry.id()).isTrue();
        assertThat(entry.path()).startsWith("/").doesNotContain(" ", "<", ">", "\"");
        // Tomcat rejects an encoded slash in the path (not in the query string).
        assertThat(entry.route()).doesNotContain("%2F");
        assertThat(entry.path().split("[?]", 2)[0]).doesNotContain("%2F");
      }
    }
  }

  @Test
  @DisplayName("Every seed entry expects the sentinel in the response")
  void seedEntriesExpectSentinel() {
    List<CatalogEntry> seeds =
        ShopCatalog.entries().stream()
            .filter(e -> e.id().startsWith("seed.") && !e.id().equals("seed.env"))
            .collect(Collectors.toList());
    assertThat(seeds).hasSizeGreaterThan(40);
    for (CatalogEntry entry : seeds) {
      assertThat(entry.checks())
          .as(entry.id())
          .anyMatch(c -> c.type() == Check.Type.BODY_CONTAINS && Seeds.SENTINEL.equals(c.value()));
    }
  }

  @Test
  @DisplayName("18 §4.3 'exception message' seeds: email, phone, card and XSS answer 500")
  void exceptionMessageSeeds() {
    List<String> errorSeeds =
        ShopCatalog.entries().stream()
            .filter(e -> e.id().startsWith("seed.") && e.expectedStatus() == 500)
            .map(CatalogEntry::id)
            .collect(Collectors.toList());
    assertThat(errorSeeds)
        .contains("seed.email.error", "seed.phone.error", "seed.card.error")
        .contains("seed.xss.error.0", "seed.xss.error.3");
    CatalogEntry email =
        ShopCatalog.entries().stream()
            .filter(e -> e.id().equals("seed.email.error"))
            .findFirst()
            .orElseThrow(AssertionError::new);
    assertThat(email.path()).contains("fail=1").contains(ShopCatalog.query(Seeds.EMAIL));
  }

  @Test
  @DisplayName("Every seed value carries ARGOS_SEED except the documented pattern variants")
  void seedValues() {
    assertThat(
            Arrays.asList(
                Seeds.EMAIL,
                Seeds.PHONE,
                Seeds.RESIDENT_NUMBER,
                Seeds.CARD,
                Seeds.USER_ID,
                Seeds.FORWARDED_FOR,
                Seeds.JWT_LIKE))
        .allMatch(v -> v.contains(Seeds.SENTINEL));
    assertThat(Seeds.ACCESS_KEY_LIKE).contains("ARGOSSEED");
    assertThat(Seeds.XSS).hasSize(4).allMatch(v -> v.contains(Seeds.SENTINEL));
    assertThat(Seeds.CSV).hasSize(4).allMatch(v -> v.contains(Seeds.SENTINEL));
    assertThat(Seeds.SQL_LITERALS).allMatch(v -> v.contains(Seeds.SENTINEL));
    assertThat(Seeds.environment().values()).allMatch(v -> v.contains(Seeds.SENTINEL));
    assertThat(
            Arrays.asList(Seeds.PHONE_PATTERN, Seeds.RESIDENT_NUMBER_PATTERN, Seeds.CARD_PATTERN))
        .noneMatch(v -> v.contains(Seeds.SENTINEL));
  }

  @Test
  @DisplayName("XSS payload paths keep '/' and encode everything else")
  void payloadPaths() {
    assertThat(ShopCatalog.pathKeepingSlashes("\"><svg/onload=window.__xss=1>"))
        .isEqualTo("%22%3E%3Csvg/onload%3Dwindow.__xss%3D1%3E");
    assertThat(ShopCatalog.query("a b+c")).isEqualTo("a%20b%2Bc");
  }
}
