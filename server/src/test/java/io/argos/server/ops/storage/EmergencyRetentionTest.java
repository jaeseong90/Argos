/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.storage.EmergencyOrder.Candidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The emergency retention steps with fake partitions, audit and insert gate (13 §8, F-S23): one
 * partition per step in the 13 §8 order while active, the audit record per drop, the pause and
 * resume of the inserts, the counter, and a failed drop that is tried again.
 */
@Tag("req:F-S23")
class EmergencyRetentionTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

  private final List<Candidate> partitions = new ArrayList<>();
  private final List<String> drops = new ArrayList<>();
  private final List<AuditEvent> audits = new ArrayList<>();
  private final List<String> gate = new ArrayList<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private boolean failNextDrop;

  private static Candidate c(String table, int daysAgo) {
    return new Candidate(table, TODAY.minusDays(daysAgo).toString().replace("-", ""), 5, 50);
  }

  private EmergencyRetention retention() {
    ArgosProperties.Ops ops =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("argos.ops", ArgosProperties.Ops.class);
    EmergencyRetention.Partitions fake =
        new EmergencyRetention.Partitions() {
          @Override
          public List<Candidate> candidates() {
            return List.copyOf(partitions);
          }

          @Override
          public void drop(Candidate candidate) {
            if (failNextDrop) {
              failNextDrop = false;
              throw new IllegalStateException("ClickHouse unavailable");
            }
            partitions.remove(candidate);
            drops.add(candidate.table() + "/" + candidate.partitionId());
          }
        };
    AuditService audit = (event, actor, target, result, before, after) -> audits.add(event);
    EmergencyRetention.InsertGate inserts =
        new EmergencyRetention.InsertGate() {
          @Override
          public void pause() {
            gate.add("pause");
          }

          @Override
          public void resume() {
            gate.add("resume");
          }
        };
    return new EmergencyRetention(ops, fake, null, audit, inserts, registry, CLOCK);
  }

  @Test
  @DisplayName(
      "T-SV-046b/c one partition per step in the 13 §8 order while active, audit and counter per"
          + " drop, today kept; below 0.85 nothing is dropped")
  void dropsOnePerStep() {
    partitions.addAll(
        List.of(
            c("xlog", 2),
            c("xlog", 0),
            c("profile_step", 1),
            c("profile_step", 0),
            c("metric", 2)));
    EmergencyRetention r = retention();
    assertThat(r.activeOrNull()).isNull();
    assertThat(r.step(0.5).dropped()).isEmpty();
    assertThat(r.activeOrNull()).isFalse();
    assertThat(r.step(0.91).dropped()).map(Candidate::table).contains("profile_step");
    assertThat(r.activeOrNull()).isTrue();
    assertThat(r.step(0.88).dropped()).map(Candidate::table).contains("metric");
    assertThat(r.step(0.86).dropped()).map(Candidate::table).contains("xlog");
    assertThat(r.step(0.86).dropped()).as("only today's partitions left").isEmpty();
    assertThat(r.step(0.84).dropped()).isEmpty();
    assertThat(r.activeOrNull()).isFalse();
    String d1 = TODAY.minusDays(1).toString().replace("-", "");
    String d2 = TODAY.minusDays(2).toString().replace("-", "");
    assertThat(drops).containsExactly("profile_step/" + d1, "metric/" + d2, "xlog/" + d2);
    assertThat(partitions)
        .extracting(Candidate::partitionId)
        .containsOnly(TODAY.toString().replace("-", ""));
    assertThat(audits)
        .containsExactly(
            AuditEvent.RETENTION_EMERGENCY_DROP,
            AuditEvent.RETENTION_EMERGENCY_DROP,
            AuditEvent.RETENTION_EMERGENCY_DROP);
    assertThat(
            registry.find("argos.retention.emergency.drops").tag("table", "xlog").counter().count())
        .isEqualTo(1);
    assertThat(r.droppedByEmergency("xlog", d2)).isTrue();
    assertThat(r.droppedByEmergency("xlog", d1)).isFalse();
    assertThat(gate).isEmpty();
  }

  @Test
  @DisplayName("T-SV-046d inserts pause at 0.96 and resume at 0.92, once each")
  void pausesAndResumes() {
    EmergencyRetention r = retention();
    r.step(0.96);
    assertThat(r.insertsPaused()).isTrue();
    r.step(0.95);
    r.step(0.94);
    r.step(0.92);
    assertThat(r.insertsPaused()).isFalse();
    assertThat(gate).containsExactly("pause", "resume");
  }

  @Test
  @DisplayName("a failed drop is not audited and is tried again at the next step")
  void failedDropRetried() {
    partitions.add(c("profile_step", 3));
    EmergencyRetention r = retention();
    failNextDrop = true;
    assertThat(r.step(0.92).dropped()).isEmpty();
    assertThat(audits).isEmpty();
    assertThat(r.step(0.92).dropped()).isPresent();
    assertThat(audits).hasSize(1);
  }

  @Test
  @DisplayName("an unknown ratio drops nothing")
  void unknownRatio() {
    partitions.add(c("profile_step", 3));
    EmergencyRetention r = retention();
    assertThat(r.step(Double.NaN).dropped()).isEmpty();
    assertThat(r.activeOrNull()).isNull();
    assertThat(Instant.now(CLOCK)).isNotNull();
  }
}
