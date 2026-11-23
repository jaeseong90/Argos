/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.RunningApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-072 / T-SEC-20 seed part (16 §5.4): shop-boot3 with secrets that carry the {@code
 * ARGOS_SEED} sentinel in its environment, in {@code -D} options and in {@code JAVA_TOOL_OPTIONS}
 * (key rule, URL credentials, JDBC, JWT, PEM, long secret, composite values); the {@code ENV_DUMP}
 * result and the agent log hold the sentinel 0 times, masked values show {@code ****(len=N)}, plain
 * values stay readable. The 30 pattern cases are agent-core {@code EnvMaskerTest}.
 */
@Tag("req:SEC-20")
@Tag("req:F-A12")
class EnvDumpSeedTest {
  static final String SENTINEL = "ARGOS_SEED";

  /** A long Unix path mixing '/' and '-': never a base64/hex candidate (C-235 (3)(c)). */
  static final String LONG_PATH = "/usr/lib/jvm/java-21-openjdk-amd64/lib/server";

  static final String JWT =
      "eyJ" + SENTINEL + "_jwt_header0.eyJ" + SENTINEL + "_jwt_pay0.sig_" + SENTINEL + "_jwt0";
  static final String AWS_KEY_ID = "AKIAWO17SEEDKEY00001";

  @TempDir Path work;

  @Test
  @DisplayName(
      "T-AG-072/T-SEC-20 ENV_DUMP of an app with ARGOS_SEED secrets in env, -D and"
          + " JAVA_TOOL_OPTIONS: sentinel 0 times in the result and the agent log, ****(len=N),"
          + " plain values kept")
  void seedSecretsAreMasked() throws Exception {
    List<Integer> jdks = CommandRuns.shopJdks();
    Assumptions.assumeFalse(jdks.isEmpty(), "no shop-boot3@<jdk> in -Pagent.e2e");
    int jdk = jdks.get(0);
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          CommandRuns.shop(work.resolve("app"), jdk, collector, new LinkedHashMap<>())
              .withEnv("DB_PASSWORD", SENTINEL + "_env_db_password")
              .withEnv("SPRING_DATASOURCE_PASSWORD", SENTINEL + "_env_spring_ds")
              .withEnv("GITHUB_TOKEN", SENTINEL + "_env_github")
              .withEnv("DATABASE_URL", "postgres://app:" + SENTINEL + "_env_url_pw@db:5432/shop")
              .withEnv("REDIS_URL", "redis://:" + SENTINEL + "_env_redis_pw@cache:6379")
              .withEnv("SOME_VAR", JWT)
              .withEnv(
                  "NEUTRAL_PEM",
                  "-----BEGIN PRIVATE KEY-----\n" // gitleaks:allow
                      + SENTINEL
                      + "_pem_body\n-----END PRIVATE KEY-----")
              .withEnv("NEUTRAL_LONG", SENTINEL + "_long_0123456789abcdef0123")
              .withEnv("NEUTRAL_AWS", AWS_KEY_ID)
              .withEnv("WO17_PLAIN_ENV", "visible-env-value")
              .withEnv("WO17_LONG_PATH", LONG_PATH)
              .withEnv(
                  "JAVA_TOOL_OPTIONS",
                  "-Dwo17.jto.password=" + SENTINEL + "_jto_pw -Dwo17.jto.plain=visible-jto")
              .withJvmOptions(
                  "-Dwo17.api.token=" + SENTINEL + "_sysprop_token",
                  "-Dwo17.db.url=jdbc:postgresql://db/shop?user=app&password="
                      + SENTINEL
                      + "_jdbc_pw",
                  "-Dwo17.plain.marker=visible-sysprop");
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        CommandRuns.awaitEstablished(app, collector);
        CommandOutcome env = CommandRuns.run(collector, "ENV_DUMP", Map.of());
        assertThat(env.status()).as("ENV_DUMP: %s", env.error()).isEqualTo("OK");
        assertThat(env.contentType()).startsWith("text/plain");
        String text = env.payloadText();
        int sentinels = CommandRuns.count(text, SENTINEL);
        System.out.println(
            "[wo17-env] jdk="
                + jdk
                + " bytes="
                + env.payload().size()
                + " sentinel="
                + sentinels
                + " masked="
                + CommandRuns.count(text, "****(len="));
        assertThat(sentinels).as("ARGOS_SEED occurrences in the ENV_DUMP result").isZero();
        assertThat(text).doesNotContain(AWS_KEY_ID).doesNotContain(collector.token());
        assertThat(text)
            .contains("DB_PASSWORD=****(len=")
            .contains("DATABASE_URL=postgres://app:****(len=")
            .contains("REDIS_URL=redis://:****(len=")
            .contains("SOME_VAR=****(len=" + JWT.length() + ")")
            .contains("NEUTRAL_PEM=****(len=")
            .contains("JAVA_TOOL_OPTIONS=-Dwo17.jto.password=****(len=")
            .contains("-Dwo17.jto.plain=visible-jto")
            .contains("wo17.api.token=****(len=")
            .contains("wo17.db.url=jdbc:postgresql://db/shop?user=****(len=3)&password=****(len=")
            .contains("WO17_PLAIN_ENV=visible-env-value")
            .contains("WO17_LONG_PATH=" + LONG_PATH)
            .contains("wo17.plain.marker=visible-sysprop")
            .contains("-Dwo17.plain.marker=visible-sysprop");
        assertThat(pathLine(text)).as("the PATH line is present and not masked").isNotNull();
        assertThat(pathLine(text)).doesNotStartWith("****(len=");
        String log = app.agentLog();
        assertThat(CommandRuns.count(log, SENTINEL)).as("ARGOS_SEED in the agent log").isZero();
      } finally {
        app.close();
        CommandRuns.keepEvidence("env-seed-" + jdk, app);
      }
      assertThat(CommandRuns.count(app.agentLog(), SENTINEL)).isZero();
    }
  }

  /** The value of the {@code PATH} line of the env section (any case of the key), or null. */
  private static String pathLine(String text) {
    for (String line : text.split("\n", -1)) {
      if (line.regionMatches(true, 0, "PATH=", 0, 5)) {
        return line.substring(5);
      }
    }
    return null;
  }
}
