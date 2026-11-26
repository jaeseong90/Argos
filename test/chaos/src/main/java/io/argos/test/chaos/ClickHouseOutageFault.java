/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * T-CH-009 "ClickHouse 30분 중단" (18 §11.1): {@code docker pause} of ClickHouse for the fault's
 * length while the agent keeps sending; the server acknowledges into its WAL and loads it after
 * {@code docker unpause}. Extra rows: the WAL stays below 10 GiB ({@code argos_wal_bytes}, sampled
 * every 5 s) and grew during the pause (reference). Re-ingest and D-13 are the common rows (every
 * transaction stored once after the drain). The FakeAgent references of the same invariants are the
 * server's ClickHouseOutageIT and ClickHouseOutageE2eIT (T-ACC-13 (c), {@code
 * -Pserver.chOutageMinutes=30}).
 */
public final class ClickHouseOutageFault implements Fault {

  /** 18 §11.1: WAL below 10 GiB. */
  static final double WAL_LIMIT_BYTES = 10.0 * 1024 * 1024 * 1024;

  /** WAL sampling interval. */
  static final long SAMPLE_MS = 5_000;

  @Override
  public String describe() {
    return "docker pause of ClickHouse for the fault length, then docker unpause";
  }

  @Override
  public void inject(ScenarioContext c) {
    c.put("walBefore", c.stack().walBytes());
    c.stack().pauseClickHouse();
  }

  @Override
  public void during(ScenarioContext c, Instant until) throws Exception {
    double max = 0;
    int samples = 0;
    while (Instant.now().isBefore(until)) {
      OptionalDouble wal = c.stack().walBytes();
      if (wal.isPresent()) {
        max = Math.max(max, wal.getAsDouble());
        samples++;
      }
      Thread.sleep(
          Math.max(0, Math.min(SAMPLE_MS, until.toEpochMilli() - System.currentTimeMillis())));
    }
    c.put("walMax", max);
    c.put("walSamples", samples);
  }

  @Override
  public void remove(ScenarioContext c) {
    c.stack().unpauseClickHouse();
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    double max = (Double) c.get("walMax");
    int samples = (Integer) c.get("walSamples");
    OptionalDouble before = (OptionalDouble) c.get("walBefore");
    rows.add(
        new Check(
            c.id(),
            "WAL while ClickHouse is paused",
            "< 10 GiB",
            String.format(
                Locale.ROOT,
                "max %.1f MiB (%d samples), before %s",
                max / 1048576.0,
                samples,
                before.isPresent()
                    ? String.format(Locale.ROOT, "%.1f MiB", before.getAsDouble() / 1048576.0)
                    : "-"),
            Check.Kind.INVARIANT,
            Judge.invariant(samples > 0 && max < WAL_LIMIT_BYTES),
            "argos_wal_bytes of /actuator/prometheus every 5 s"));
    rows.add(
        new Check(
            c.id(),
            "WAL grew during the pause (reference)",
            "> before",
            before.isPresent()
                ? String.format(Locale.ROOT, "%+.1f MiB", (max - before.getAsDouble()) / 1048576.0)
                : "-",
            Check.Kind.INFO,
            Verdict.INFO,
            "shows that the server kept acknowledging into its WAL"));
    return rows;
  }
}
