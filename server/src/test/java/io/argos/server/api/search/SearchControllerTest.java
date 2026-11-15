/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.traces.TraceService;
import io.argos.server.api.traces.TraceView;
import io.argos.server.api.transactions.Ids;
import io.argos.server.api.transactions.TransactionLookup;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.AuthTestProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The type detection of the global search (14 §4.11, 13 §2 tx_id format) and the application
 * permission check of the applications its id lookups find (SEC-50, 16 §7.11). In 1.0 every user
 * sees every application, so the check never refuses on a real server; a policy that refuses one
 * application shows that the found applications are checked.
 */
class SearchControllerTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

  private static long txId(Instant start) {
    return ((start.toEpochMilli() - Ids.TX_EPOCH_MS) << 23) | (0x123L << 11) | 7;
  }

  @Test
  @Tag("req:F-U14")
  @DisplayName(
      "auto reads a 2026 transaction id (18 digits) and a later one (19-20 digits) as tx; short"
          + " numbers, numbers whose time is in the future and non-digits are not")
  void autoTransactionIds() {
    Instant now = NOW;
    String today = Long.toUnsignedString(txId(now.minusSeconds(60)));
    assertThat(today).hasSize(18);
    assertThat(SearchController.looksLikeTxId(today, now)).isTrue();
    // An id of 2030 (19 digits) when that year has come.
    Instant later = Instant.parse("2030-06-01T00:00:00Z");
    String id2030 = Long.toUnsignedString(txId(later));
    assertThat(id2030).hasSize(19);
    assertThat(SearchController.looksLikeTxId(id2030, later.plusSeconds(1))).isTrue();
    // The same id seen in 2026 lies in the future: text.
    assertThat(SearchController.looksLikeTxId(id2030, now)).isFalse();
    // Up to a day of clock skew ahead is accepted.
    assertThat(
            SearchController.looksLikeTxId(Long.toUnsignedString(txId(now.plusSeconds(3600))), now))
        .isTrue();
    assertThat(SearchController.looksLikeTxId("12345678901234", now)).as("14 digits").isFalse();
    assertThat(SearchController.looksLikeTxId("1".repeat(21), now)).isFalse();
    assertThat(SearchController.looksLikeTxId("18446744073709551616", now))
        .as("above 64 bits")
        .isFalse();
    assertThat(SearchController.looksLikeTxId("22578668038735671x", now)).isFalse();
  }

  /** An access policy that refuses one application and records every check. */
  private static final class RecordingPolicy extends AppAccessPolicy {

    private final long refused;
    private final List<Long> checked = new ArrayList<>();

    RecordingPolicy(long refused) {
      this.refused = refused;
    }

    @Override
    public void check(ArgosPrincipal principal, long appId) {
      checked.add(appId);
      if (appId == refused) {
        throw new ApiException(ErrorCode.FORBIDDEN);
      }
    }
  }

  private static QueryRow row(Map<String, Object> values) {
    return new QueryRow() {
      @Override
      public List<String> columns() {
        return List.copyOf(values.keySet());
      }

      @Override
      public Object get(String column) {
        return values.get(column);
      }
    };
  }

  private static QueryRow node(int appId) {
    return row(
        Map.of(
            "app_id",
            (long) appId,
            "ts_ms",
            NOW.minusSeconds(600).toEpochMilli(),
            "service_id",
            11L,
            "elapsed_ms",
            25L));
  }

  private static final QuerySources SOURCES =
      new QuerySources(AuthTestProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));

  /**
   * The controller with stub collaborators (plain subclasses, no database and no mock agent): the
   * dictionary names every service {@code GET /orders}.
   */
  private static SearchController controller(
      TransactionLookup lookup, TraceService traces, AppAccessPolicy policy) {
    DictionaryNames names =
        new DictionaryNames(new SimpleDriverDataSource()) {
          @Override
          public String name(long appId, DictionaryNames.Kind kind, long hash) {
            return "GET /orders";
          }
        };
    return new SearchController(lookup, traces, null, names, null, null, policy, SOURCES);
  }

  /** A transaction lookup that finds {@code found} for every id. */
  private static TransactionLookup lookupFinding(TransactionLookup.Found found) {
    return new TransactionLookup(null) {
      @Override
      public Optional<Found> tryFind(long txId, Instant tsHint, int appHint) {
        return Optional.of(found);
      }
    };
  }

  /** A trace reader whose every window holds {@code nodes}. */
  private static TraceService tracesHolding(List<QueryRow> nodes) {
    return new TraceService(null, null, null, SOURCES, AuthTestProperties.defaults()) {
      @Override
      public List<QueryRow> nodes(String traceHex, TraceView.Window window) {
        return nodes;
      }
    };
  }

  @Test
  @Tag("req:SEC-50")
  @DisplayName(
      "a transaction id found without an app restriction checks the found application: a refusal"
          + " is passed on, an allowed one returns the match")
  void txChecksTheFoundApplication() {
    long id = txId(NOW.minusSeconds(600));
    TransactionLookup lookup =
        lookupFinding(
            new TransactionLookup.Found(List.of(node(5)), TransactionLookup.Path.RESTORED));
    String q = Long.toUnsignedString(id);

    RecordingPolicy refuses5 = new RecordingPolicy(5);
    assertThatThrownBy(
            () -> controller(lookup, null, refuses5).search(q, "tx", null, null, null, null))
        .isInstanceOf(ApiException.class)
        .hasMessage("forbidden");
    assertThat(refuses5.checked).containsExactly(5L);

    RecordingPolicy refuses6 = new RecordingPolicy(6);
    assertThat(
            controller(lookup, null, refuses6)
                .search(q, "auto", null, null, null, null)
                .data()
                .matches())
        .hasSize(1);
    assertThat(refuses6.checked).containsExactly(5L);
  }

  @Test
  @Tag("req:SEC-50")
  @DisplayName(
      "a trace id checks every application of the trace's transactions (as traces_get does): a"
          + " refused child application is passed on")
  void traceChecksEveryApplication() {
    TraceService traces = tracesHolding(List.of(node(5), node(6), node(6)));
    // Not an Argos-generated id (no plausible time in the first 4 bytes): the 24 h default.
    String trace = "00000001" + "0".repeat(23) + "9";

    RecordingPolicy refuses6 = new RecordingPolicy(6);
    assertThatThrownBy(
            () -> controller(null, traces, refuses6).search(trace, "trace", null, null, null, null))
        .isInstanceOf(ApiException.class)
        .hasMessage("forbidden");
    assertThat(refuses6.checked).containsExactly(5L, 6L);

    RecordingPolicy allows = new RecordingPolicy(-1);
    assertThat(
            controller(null, traces, allows)
                .search(trace, "auto", null, null, null, null)
                .data()
                .matches())
        .hasSize(1);
    assertThat(allows.checked).containsExactly(5L, 6L);
  }
}
