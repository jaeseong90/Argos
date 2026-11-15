/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.Envelope;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import io.argos.server.web.RequestIdFilter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/**
 * The envelope of 14 §2.5 as the JSON writer of the server produces it: field names and order, the
 * C-6 name {@code retention_exceeded}, ISO-8601 UTC with milliseconds, and the absent-versus- null
 * rule (only {@code next_cursor} is ever {@code null}).
 */
@Tag("req:DSN-14-04")
@Tag("req:D-06")
@Tag("req:D-07")
class ResponseEnvelopeTest {

  /** The mapper Spring Boot builds has the same defaults for these types (Jackson 3). */
  private static final JsonMapper JSON =
      JsonMapper.builder().addModule(new UntrustedStringsModule()).build();

  private static final Instant FROM = Instant.parse("2026-10-23T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-23T01:00:00Z");
  private static final Instant GENERATED = Instant.parse("2026-10-23T01:02:03.456789Z");

  private static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  @Test
  @DisplayName("T-API-001b every 14 §2.5 field in its order, with C-6 retention_exceeded")
  void fullEnvelope() {
    ResponseEnvelope<Map<String, Object>> e =
        ResponseEnvelope.builder(Map.<String, Object>of("x", 1))
            .requestId("01JABCDEFGHJKMNPQRSTVWXYZ0")
            .generatedAt(GENERATED)
            .range(FROM, TO)
            .source(Source.RAW)
            .truncated(false)
            .counts(100_000, 143_210)
            .sampling(true, "error OR slow OR cityHash64(tx_id) % 2 = 0")
            .nextCursor(null)
            .retentionExceeded(false)
            .outages(List.of())
            .slowThresholdMs(1000)
            .build();
    assertThat(json(e))
        .isEqualTo(
            "{\"data\":{\"x\":1},\"meta\":{\"request_id\":\"01JABCDEFGHJKMNPQRSTVWXYZ0\","
                + "\"generated_at\":\"2026-10-23T01:02:03.456Z\","
                + "\"from\":\"2026-10-23T00:00:00Z\",\"to\":\"2026-10-23T01:00:00Z\","
                + "\"source\":\"raw\",\"truncated\":false,\"shown\":100000,\"total\":143210,"
                + "\"sampling\":{\"applied\":true,\"rule\":\"error OR slow OR cityHash64(tx_id)"
                + " % 2 = 0\"},\"next_cursor\":null,\"retention_exceeded\":false,"
                + "\"outages\":[],\"slow_threshold_ms\":1000}}");
  }

  @Test
  @DisplayName(
      "T-API-001b absent fields are left out, never null; next_cursor null is the last page")
  void absentVersusNull() {
    String minimal =
        json(ResponseEnvelope.builder(List.of()).requestId("r1").generatedAt(GENERATED).build());
    assertThat(minimal)
        .isEqualTo(
            "{\"data\":[],\"meta\":{\"request_id\":\"r1\","
                + "\"generated_at\":\"2026-10-23T01:02:03.456Z\"}}");
    String lastPage =
        json(ResponseEnvelope.builder(List.of()).requestId("r1").nextCursor(null).build());
    assertThat(lastPage).contains("\"next_cursor\":null");
    String nextPage =
        json(ResponseEnvelope.builder(List.of()).requestId("r1").nextCursor("abc").build());
    assertThat(nextPage).contains("\"next_cursor\":\"abc\"");
    assertThat(minimal).doesNotContain("null");
  }

  @Test
  @DisplayName("T-API-003a a plan sets range, source, truncated=false and retention_exceeded")
  void fromPlan() {
    SourcePlan plan =
        new SourcePlan(
            FROM,
            TO,
            TableLevel.MINUTE,
            Source.MIXED,
            Duration.ofMinutes(1),
            FROM,
            60,
            List.of(new TimeSegment(TableLevel.MINUTE, FROM, TO)),
            true);
    String json =
        json(
            ResponseEnvelope.builder("d").requestId("r").generatedAt(GENERATED).plan(plan).build());
    assertThat(json)
        .isEqualTo(
            "{\"data\":\"d\",\"meta\":{\"request_id\":\"r\","
                + "\"generated_at\":\"2026-10-23T01:02:03.456Z\","
                + "\"from\":\"2026-10-23T00:00:00Z\",\"to\":\"2026-10-23T01:00:00Z\","
                + "\"source\":\"mixed\",\"truncated\":false,\"retention_exceeded\":true}}");
  }

  @Test
  @DisplayName(
      "T-API-001b Envelope.of (authentication, setup, users) writes the one meta: request_id,"
          + " generated_at, and truncated only when a list was cut")
  void shortEnvelope() {
    assertThat(Envelope.class.getRecordComponents()[1].getType())
        .as("one meta record for every envelope")
        .isEqualTo(ResponseEnvelope.Meta.class);
    MDC.put(RequestIdFilter.MDC_KEY, "01JREQ");
    try {
      Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      Envelope<Map<String, Object>> plain = Envelope.of(Map.<String, Object>of("x", 1));
      Instant after = Instant.now();
      Instant generated = plain.meta().generatedAt();
      assertThat(generated).isBetween(before, after);
      assertThat(generated.truncatedTo(ChronoUnit.MILLIS))
          .as("milliseconds (14 §2.4)")
          .isEqualTo(generated);
      assertThat(json(plain))
          .isEqualTo(
              "{\"data\":{\"x\":1},\"meta\":{\"request_id\":\"01JREQ\",\"generated_at\":\""
                  + generated
                  + "\"}}");
      Envelope<List<Integer>> cut = Envelope.of(List.of(1), true);
      assertThat(json(cut))
          .isEqualTo(
              "{\"data\":[1],\"meta\":{\"request_id\":\"01JREQ\",\"generated_at\":\""
                  + cut.meta().generatedAt()
                  + "\",\"truncated\":true}}");
      Envelope<List<Integer>> whole = Envelope.of(List.of(1), false);
      assertThat(json(whole))
          .isEqualTo(
              "{\"data\":[1],\"meta\":{\"request_id\":\"01JREQ\",\"generated_at\":\""
                  + whole.meta().generatedAt()
                  + "\"}}");
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
    }
  }

  @Test
  @DisplayName("sampling without an applied rule and outages with an open end")
  void samplingAndOutages() {
    String json =
        json(
            ResponseEnvelope.builder(1)
                .requestId("r")
                .generatedAt(GENERATED)
                .sampling(false, "ignored")
                .outages(
                    List.of(
                        new ResponseEnvelope.Outage(FROM, TO, "session_lost"),
                        new ResponseEnvelope.Outage(TO, null, "server_down")))
                .build());
    assertThat(json)
        .contains("\"sampling\":{\"applied\":false}")
        .contains(
            "\"outages\":[{\"from\":\"2026-10-23T00:00:00Z\",\"to\":\"2026-10-23T01:00:00Z\","
                + "\"reason\":\"session_lost\"},{\"from\":\"2026-10-23T01:00:00Z\","
                + "\"reason\":\"server_down\"}]");
  }
}
