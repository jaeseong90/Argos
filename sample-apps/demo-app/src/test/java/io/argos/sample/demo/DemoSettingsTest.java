/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Settings of the demo app from the environment (17 §3.2 demo profile, WO-41). */
class DemoSettingsTest {

  @TempDir Path dir;

  private Map<String, String> base() throws IOException {
    Path file = dir.resolve("demo_db_password");
    Files.writeString(file, "s3cret-from-file\n", StandardCharsets.UTF_8);
    Map<String, String> m = new HashMap<>();
    m.put("demo.db.password-file", file.toString());
    return m;
  }

  @Test
  @DisplayName(
      "Defaults: order role, payment-api peer, demo-db database, 120 s wait, schema demo_order")
  void defaults() throws IOException {
    DemoSettings s = DemoSettings.from(base()::get);
    assertThat(s.role()).isEqualTo("order");
    assertThat(s.isOrder()).isTrue();
    assertThat(s.peerUrl()).hasToString("http://sample-app-b:8080");
    assertThat(s.dbUrl()).isEqualTo("jdbc:postgresql://demo-db:5432/demo");
    assertThat(s.dbUser()).isEqualTo("demo");
    assertThat(s.dbWait()).isEqualTo(Duration.ofMinutes(2));
    assertThat(s.schema()).isEqualTo("demo_order");
  }

  @Test
  @DisplayName("Payment role: own schema, no peer; role names are case-insensitive")
  void paymentRole() throws IOException {
    Map<String, String> env = base();
    env.put("demo.role", " Payment ");
    env.put("demo.peer-url", "http://ignored:1");
    DemoSettings s = DemoSettings.from(env::get);
    assertThat(s.role()).isEqualTo("payment");
    assertThat(s.isOrder()).isFalse();
    assertThat(s.peerUrl()).isNull();
    assertThat(s.schema()).isEqualTo("demo_payment");
  }

  @Test
  @DisplayName(
      "Unknown role, non-PostgreSQL URL and non-http peer are refused with the variable name")
  void invalidValues() throws IOException {
    Map<String, String> role = base();
    role.put("demo.role", "inventory");
    assertThatThrownBy(() -> DemoSettings.from(role::get))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DEMO_ROLE");
    Map<String, String> url = base();
    url.put("demo.db.url", "jdbc:h2:mem:demo");
    assertThatThrownBy(() -> DemoSettings.from(url::get)).hasMessageContaining("DEMO_DB_URL");
    Map<String, String> peer = base();
    peer.put("demo.peer-url", "ftp://payment");
    assertThatThrownBy(() -> DemoSettings.from(peer::get)).hasMessageContaining("PEER_URL");
    Map<String, String> wait = base();
    wait.put("demo.db.wait", "soon");
    assertThatThrownBy(() -> DemoSettings.from(wait::get)).hasMessageContaining("DEMO_DB_WAIT");
  }

  @Test
  @DisplayName("T-SEC-74 password from DEMO_DB_PASSWORD_FILE only: DEMO_DB_PASSWORD is refused")
  void passwordFromFileOnly() throws IOException {
    Map<String, String> env = base();
    assertThat(DemoSettings.from(env::get).readDbPassword()).isEqualTo("s3cret-from-file");

    Map<String, String> plain = base();
    plain.put("demo.db.password", "plaintext-in-env");
    assertThatThrownBy(() -> DemoSettings.from(plain::get))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DEMO_DB_PASSWORD_FILE")
        .hasMessageNotContaining("plaintext-in-env");

    Map<String, String> none = new HashMap<>();
    assertThatThrownBy(() -> DemoSettings.from(none::get))
        .hasMessageContaining("DEMO_DB_PASSWORD_FILE is required");
  }

  @Test
  @DisplayName(
      "Password file errors name the file, never the content; empty and oversized files fail")
  void passwordFileErrors() throws IOException {
    Map<String, String> missing = new HashMap<>();
    missing.put("demo.db.password-file", dir.resolve("absent").toString());
    assertThatThrownBy(() -> DemoSettings.from(missing::get).readDbPassword())
        .hasMessageContaining("absent");

    Path empty = dir.resolve("empty");
    Files.writeString(empty, "\n", StandardCharsets.UTF_8);
    Map<String, String> emptyEnv = Map.of("demo.db.password-file", empty.toString());
    assertThatThrownBy(() -> DemoSettings.from(emptyEnv::get).readDbPassword())
        .hasMessageContaining("is empty");

    Path big = dir.resolve("big");
    Files.writeString(big, "x".repeat(DemoSettings.MAX_PASSWORD_BYTES + 1), StandardCharsets.UTF_8);
    Map<String, String> bigEnv = Map.of("demo.db.password-file", big.toString());
    assertThatThrownBy(() -> DemoSettings.from(bigEnv::get).readDbPassword())
        .hasMessageContaining("larger than")
        .hasMessageNotContaining("xxxx");

    Path crlf = dir.resolve("crlf");
    Files.writeString(crlf, "pw\r\n", StandardCharsets.UTF_8);
    Map<String, String> crlfEnv = Map.of("demo.db.password-file", crlf.toString());
    assertThat(DemoSettings.from(crlfEnv::get).readDbPassword()).isEqualTo("pw");
  }

  @Test
  @DisplayName("Durations: 30s, 2m, 500ms, 1h, PT2M and plain seconds; zero is refused")
  void durations() {
    Duration fallback = Duration.ofSeconds(9);
    assertThat(DemoSettings.duration("X", null, fallback)).isEqualTo(fallback);
    assertThat(DemoSettings.duration("X", "30s", fallback)).isEqualTo(Duration.ofSeconds(30));
    assertThat(DemoSettings.duration("X", "2m", fallback)).isEqualTo(Duration.ofMinutes(2));
    assertThat(DemoSettings.duration("X", "500ms", fallback)).isEqualTo(Duration.ofMillis(500));
    assertThat(DemoSettings.duration("X", "1h", fallback)).isEqualTo(Duration.ofHours(1));
    assertThat(DemoSettings.duration("X", "PT2M", fallback)).isEqualTo(Duration.ofMinutes(2));
    assertThat(DemoSettings.duration("X", "45", fallback)).isEqualTo(Duration.ofSeconds(45));
    assertThatThrownBy(() -> DemoSettings.duration("X", "0s", fallback))
        .hasMessageContaining("positive");
  }

  @Test
  @DisplayName("The database URL in logs drops its query part")
  void urlForLog() throws IOException {
    Map<String, String> env = base();
    env.put("demo.db.url", "jdbc:postgresql://db:5432/demo?sslmode=disable&user=x");
    assertThat(DemoSettings.from(env::get).dbUrlForLog())
        .isEqualTo("jdbc:postgresql://db:5432/demo");
  }
}
