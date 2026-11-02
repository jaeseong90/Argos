/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 01 §7.3 protocol deviations and the 18 §7.1 environment record. */
@Tag("req:DSN-18-07")
class ProtocolAndEnvTest {

  @Test
  @DisplayName("a set with the protocol's parameters has no deviation")
  void protocolSets() {
    assertThat(Protocol.deviations(Fixtures.loadSet("process", false, "default"))).isEmpty();
    assertThat(Protocol.deviations(Fixtures.loadSet("container", false, "default"))).isEmpty();
    assertThat(Protocol.notes(Fixtures.loadSet("container", false, "default"))).hasSize(1);
    assertThat(Protocol.deviations(Fixtures.startupSet())).isEmpty();
  }

  @Test
  @DisplayName("a smoke set (short windows, one round, k6 on the app machine) deviates")
  void smokeSetDeviates() {
    Map<String, Object> set = Fixtures.loadSet("process", false, "default");
    Map<String, Object> p = new LinkedHashMap<>(Json.object(set.get("parameters")));
    p.put("rounds", 1);
    p.put("warmupSeconds", 30);
    p.put("durationSeconds", 60);
    p.put("k6Ssh", null);
    p.put("jvmOptions", List.of("-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"));
    set.put("parameters", p);
    assertThat(Protocol.deviations(set))
        .containsExactly(
            "1 rounds (protocol 3)",
            "warm-up 30 s (protocol 180 s)",
            "window 60 s (protocol 600 s)",
            "JVM options without -Xms2g (01 §7.3 item 2)",
            "JVM options without -Xmx2g (01 §7.3 item 2)",
            "load generator on the app machine (01 §7.3 item 1: k6 on another machine, --k6-ssh)");
    Map<String, Object> container = Fixtures.loadSet("container", false, "default");
    Map<String, Object> cp = new LinkedHashMap<>(Json.object(container.get("parameters")));
    cp.put("memory", "2g");
    container.put("parameters", cp);
    assertThat(Protocol.deviations(container))
        .containsExactly(
            "container limits --cpus 2 --memory 2g (01 §7.3 item 9: --cpus=2 --memory=4g)");
  }

  @Test
  @DisplayName("a startup set without the heavy app, CDS off or 10 starts deviates")
  void startupDeviations() {
    Map<String, Object> set = Fixtures.startupSet();
    Map<String, Object> p = new LinkedHashMap<>(Json.object(set.get("parameters")));
    p.put("runsPerVariant", 1);
    p.put("apps", List.of("shop-boot3"));
    p.put("cds", List.of("default"));
    set.put("parameters", p);
    assertThat(Protocol.deviations(set))
        .containsExactly(
            "1 starts per variant (protocol 10)",
            "no shop-heavy starts (N-04 heavy app, N-27)",
            "CDS modes [default] (protocol: default and off)");
  }

  @Test
  @DisplayName("environment record: valid, overridden (governor) and missing")
  void environmentRecord(@TempDir Path dir) throws IOException {
    assertThat(EnvRecord.problems(Fixtures.envRecord())).isEmpty();
    assertThat(
            EnvRecord.problems(
                Fixtures.envRecord("CPU governor powersave, not performance (overridden)")))
        .containsExactly("environment: CPU governor powersave, not performance (overridden)");
    assertThat(EnvRecord.problems(null))
        .containsExactly("no bench-env.sh environment record (18 §7.1)");
    Map<String, Object> notValid = Fixtures.envRecord();
    notValid.put("valid", false);
    assertThat(EnvRecord.problems(notValid))
        .containsExactly("environment: record not marked valid");
    Path file = dir.resolve("env-record.json");
    FileTools.writeJson(file, Fixtures.envRecord());
    assertThat(EnvRecord.read(file)).containsEntry("machineProfile", "argos-bench-app");
    FileTools.writeJson(file, Map.of("tool", "other"));
    assertThatThrownBy(() -> EnvRecord.read(file)).isInstanceOf(UsageException.class);
    assertThatThrownBy(() -> EnvRecord.read(dir.resolve("missing.json")))
        .isInstanceOf(UsageException.class);
    assertThat(EnvRecord.ofSet(dir.resolve("none"))).isNull();
  }
}
