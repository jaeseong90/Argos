/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.KeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The fixed environment-dump masking of 16 §5.4 (SEC-20): the 30 cases {@code ST-20-1} to {@code
 * ST-20-30} (16 §5.4 lists 29 secrets and 4 negatives; the negatives are case {@code ST-20-30}),
 * the value rules on their own, composite values and operator patterns that can only add.
 */
@Tag("req:SEC-20")
@Tag("req:F-A12")
class EnvMaskerTest {
  private static final String JWT =
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
          + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
  private static final String PEM =
      "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----";
  private static final String TOKEN = "argos_" + repeat('A', 20) + repeat('b', 23);

  private final EnvMasker masker = EnvMasker.builtIn();

  /** Source of a case: env variable, system property. */
  enum Kind {
    ENV,
    PROPERTY
  }

  static Stream<Arguments> secrets() {
    return Stream.of(
        whole("ST-20-1", "DB_PASSWORD", "s3cr3t!"),
        Arguments.of("ST-20-2", Kind.PROPERTY, "db.password", "hunter2", "****(len=7)"),
        whole("ST-20-3", "SPRING_DATASOURCE_PASSWORD", "pa55word"),
        whole("ST-20-4", "ARGOS_TOKEN", TOKEN),
        whole("ST-20-5", "AWS_SECRET_ACCESS_KEY", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
        whole("ST-20-6", "AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE"),
        whole(
            "ST-20-7",
            "GITHUB_TOKEN",
            "ghp_16C7e42F292c6912E7710c838347Ae178B4a"), // gitleaks:allow
        whole("ST-20-8", "JWT_SECRET", "jwt-signing-secret"),
        whole("ST-20-9", "API_KEY", "k-123"),
        whole("ST-20-10", "apiKey", "k-456"),
        whole("ST-20-11", "X-Api-Key", "k-789"),
        whole("ST-20-12", "PRIVATE_KEY", PEM),
        whole("ST-20-13", "SSL_KEYSTORE_PASSWORD", "changeit"),
        whole("ST-20-14", "KEYSTORE_PASS", "changeit2"),
        whole("ST-20-15", "SESSION_SECRET", "sess"),
        whole("ST-20-16", "COOKIE_SECRET", "cook"),
        whole("ST-20-17", "SALT", "pepper"),
        whole("ST-20-18", "OAUTH_CLIENT_SECRET", "oauth-secret"),
        whole("ST-20-19", "AUTH_HEADER", "Bearer " + JWT),
        whole("ST-20-20", "LICENSE_KEY", "LIC-1"),
        whole("ST-20-21", "OTP_SEED", "JBSWY3DPEHPK3PXP"),
        Arguments.of(
            "ST-20-22", Kind.ENV, "DATABASE_URL", "postgres://u:p@h", "postgres://u:****(len=1)@h"),
        Arguments.of(
            "ST-20-23",
            Kind.ENV,
            "JAVA_TOOL_OPTIONS",
            "-Dspring.datasource.password=x -Xmx2g",
            "-Dspring.datasource.password=****(len=1) -Xmx2g"),
        Arguments.of("ST-20-24", Kind.ENV, "REDIS_URL", "redis://:p@h", "redis://:****(len=1)@h"),
        whole("ST-20-25", "MAIL_PASSWORD", "mailpw"),
        whole("ST-20-26", "SMTP_PWD", "smtppw"),
        whole("ST-20-27", "SIGNING_KEY", "sign"),
        whole("ST-20-28", "ENCRYPTION_KEY", "enc"),
        whole("ST-20-29", "SOME_VAR", JWT));
  }

  private static Arguments whole(String id, String key, String value) {
    return Arguments.of(id, Kind.ENV, key, value, "****(len=" + value.length() + ")");
  }

  @ParameterizedTest(name = "T-AG-072/T-SEC-20 {0} {2} is masked")
  @MethodSource("secrets")
  void secretsAreMasked(String id, Kind kind, String key, String value, String expected) {
    String masked = kind == Kind.ENV ? masker.maskEnv(key, value) : masker.maskProperty(key, value);
    assertThat(masked).as(id).isEqualTo(expected);
    if (!expected.contains("://")) {
      assertThat(masked).doesNotContain("=x ").doesNotContain(value.length() > 3 ? value : "\0");
    }
  }

  @Test
  @DisplayName("T-AG-072/T-SEC-20 ST-20-30 PATH, HOME, JAVA_HOME and LANG are not masked")
  void negatives() {
    Map<String, String> plain = new LinkedHashMap<String, String>();
    plain.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    plain.put("HOME", "/home/app");
    plain.put("JAVA_HOME", "/opt/java/openjdk");
    plain.put("LANG", "en_US.UTF-8");
    for (Map.Entry<String, String> e : plain.entrySet()) {
      assertThat(masker.maskEnv(e.getKey(), e.getValue())).as(e.getKey()).isEqualTo(e.getValue());
    }
    // Windows forms of the same variables stay too.
    assertThat(masker.maskEnv("JAVA_HOME", "C:\\Program Files\\Java\\jdk-21"))
        .isEqualTo("C:\\Program Files\\Java\\jdk-21");
    assertThat(masker.maskEnv("PATH", "C:\\Windows\\system32;C:\\Windows"))
        .isEqualTo("C:\\Windows\\system32;C:\\Windows");
  }

  @Test
  @DisplayName(
      "T-AG-072/T-SEC-20 ST-20-30 long Unix paths mixing '/' with '-' or '_' are not masked"
          + " (C-235 (3)(c))")
  void longPathsAreNotMasked() {
    Map<String, String> plain = new LinkedHashMap<String, String>();
    plain.put("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64");
    plain.put("JRE_HOME", "/usr/lib/jvm/java-17-openjdk-amd64/jre");
    plain.put("CATALINA_BASE", "/var/lib/tomcat-10/instances/shop-service-blue");
    plain.put("APP_DIR", "/opt/my_company/shop_service/current_release");
    plain.put("PATH", "/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/local/sbin:/usr/bin");
    plain.put("LD_LIBRARY_PATH", "/opt/oracle/instantclient_21_13/lib-x86_64-linux-gnu");
    for (Map.Entry<String, String> e : plain.entrySet()) {
      assertThat(e.getValue().length()).as(e.getKey()).isGreaterThanOrEqualTo(32);
      assertThat(masker.maskEnv(e.getKey(), e.getValue())).as(e.getKey()).isEqualTo(e.getValue());
    }
    assertThat(masker.maskArgument("-Djava.home=/usr/lib/jvm/java-21-openjdk-amd64"))
        .isEqualTo("-Djava.home=/usr/lib/jvm/java-21-openjdk-amd64");
  }

  @Test
  @DisplayName(
      "T-AG-072/T-SEC-20 a run of 32+ characters within one alphabet (base64, URL-safe base64,"
          + " hex) is still masked next to a character of the other alphabet (C-235 (3)(c))")
  void singleAlphabetRunsAreMasked() {
    String standard = "Ab3+/Cd4Ef5Gh6Ij7Kl8Mn9Op0Qr1St2Uv3Wx4==";
    String urlSafe = "Ab3-_Cd4Ef5Gh6Ij7Kl8Mn9Op0Qr1St2Uv3Wx4";
    String hex = "0123456789abcdef0123456789abcdef";
    assertThat(masker.maskEnv("NEUTRAL", standard))
        .isEqualTo("****(len=" + standard.length() + ")");
    assertThat(masker.maskEnv("NEUTRAL", urlSafe)).isEqualTo("****(len=" + urlSafe.length() + ")");
    // A standard-base64 secret after "-" or "_" (not in its alphabet) is still found.
    assertThat(masker.maskEnv("NEUTRAL", "prefix-" + standard))
        .isEqualTo("prefix-****(len=" + standard.length() + ")");
    // A URL-safe secret after "/" (not in its alphabet) is still found.
    assertThat(masker.maskEnv("NEUTRAL", "https://h/" + urlSafe))
        .isEqualTo("https://h/****(len=" + urlSafe.length() + ")");
    // Hex is a subset of both alphabets: masked between "-" and "/" too.
    assertThat(masker.maskEnv("NEUTRAL", "/opt/x-" + hex + "/y")).contains("****(len=");
    assertThat(masker.maskEnv("NEUTRAL", "/opt/x-" + hex + "/y")).doesNotContain(hex);
  }

  @Test
  @DisplayName("T-AG-072/T-SEC-20 value rules mask under a key the key rule does not match")
  void valueRulesAlone() {
    assertThat(masker.maskEnv("NEUTRAL", "AKIAIOSFODNN7EXAMPLE")).isEqualTo("****(len=20)");
    assertThat(masker.maskEnv("NEUTRAL", "id=AKIAIOSFODNN7EXAMPLE;x"))
        .isEqualTo("id=****(len=20);x");
    assertThat(masker.maskEnv("NEUTRAL", PEM)).isEqualTo("****(len=" + PEM.length() + ")");
    assertThat(masker.maskEnv("NEUTRAL", JWT)).isEqualTo("****(len=" + JWT.length() + ")");
    String run = "c2VjcmV0LXZhbHVlLXRoYXQtaXMtbG9uZw==";
    assertThat(run.length()).isGreaterThanOrEqualTo(32);
    assertThat(masker.maskEnv("NEUTRAL", "v " + run)).isEqualTo("v ****(len=" + run.length() + ")");
    // "=" is in the base64 alphabet: "v=<run>" is one run and masked as a whole.
    assertThat(masker.maskEnv("NEUTRAL", "v=" + run))
        .isEqualTo("****(len=" + (run.length() + 2) + ")");
    String hex = "0123456789abcdef0123456789abcdef";
    assertThat(masker.maskEnv("NEUTRAL", hex)).isEqualTo("****(len=32)");
    assertThat(masker.maskEnv("NEUTRAL", hex.substring(1))).isEqualTo(hex.substring(1));
    assertThat(masker.maskEnv("NEUTRAL", "jdbc:mysql://db/shop?user=app&password=pw1"))
        .isEqualTo("jdbc:mysql://db/shop?user=****(len=3)&password=****(len=3)");
    assertThat(masker.maskEnv("NEUTRAL", "jdbc:sqlserver://db;databaseName=s;pwd=pw2;"))
        .isEqualTo("jdbc:sqlserver://db;databaseName=s;pwd=****(len=3);");
    assertThat(masker.maskEnv("NEUTRAL", "https://admin:pa55@example.test/x"))
        .isEqualTo("https://admin:****(len=4)@example.test/x");
    assertThat(masker.maskEnv("NEUTRAL", "x Bearer " + JWT))
        .isEqualTo("x Bearer ****(len=" + JWT.length() + ")");
  }

  @Test
  @DisplayName("T-AG-072/T-SEC-20 composite values and JVM arguments are masked item by item")
  void composites() {
    assertThat(
            masker.maskEnv(
                "JDK_JAVA_OPTIONS", "-Xms1g -Dapp.api.token=abc \"-Dquoted.secret=a b\" -ea"))
        .isEqualTo("-Xms1g -Dapp.api.token=****(len=3) \"-Dquoted.secret=****(len=3)\" -ea");
    assertThat(masker.maskEnv("CATALINA_OPTS", "-Ddb.pwd=zz -Dplain.value=ok"))
        .isEqualTo("-Ddb.pwd=****(len=2) -Dplain.value=ok");
    assertThat(masker.maskEnv("MY_APP_OPTS", "--server.port=8080 --mail.password=m"))
        .isEqualTo("--server.port=8080 --mail.password=****(len=1)");
    assertThat(masker.maskArgument("-Dspring.datasource.password=x"))
        .isEqualTo("-Dspring.datasource.password=****(len=1)");
    assertThat(masker.maskArgument("-Xmx2g")).isEqualTo("-Xmx2g");
    assertThat(masker.maskArgument("-Ddb.url=postgres://u:p@h/db"))
        .isEqualTo("-Ddb.url=postgres://u:****(len=1)@h/db");
    assertThat(masker.maskProperty("sun.java.command", "app.jar --api.token=t0k --port=1"))
        .isEqualTo("app.jar --api.token=****(len=3) --port=1");
    // A quoted item whose key is a secret is still masked through its value rules.
    assertThat(masker.maskEnv("JAVA_TOOL_OPTIONS", "\"-Ddb.password=a b\"")).doesNotContain("a b");
  }

  @Test
  @DisplayName("T-SEC-20 operator patterns only add to the built-in masking")
  void operatorPatternsAdd() {
    EnvMasker withUser = new EnvMasker(Arrays.asList("(?i)^corp_", "INTERNAL-[0-9]+"));
    assertThat(withUser.userPatternCount()).isEqualTo(2);
    assertThat(withUser.maskEnv("CORP_ENDPOINT", "http://x")).isEqualTo("****(len=8)");
    assertThat(withUser.maskEnv("NOTE", "see INTERNAL-42 now")).isEqualTo("see ****(len=11) now");
    assertThat(withUser.maskEnv("DB_PASSWORD", "s3cr3t")).isEqualTo("****(len=6)");
    assertThat(EnvMasker.builtIn().maskEnv("CORP_ENDPOINT", "http://x")).isEqualTo("http://x");

    // No list, an empty list, blank entries, invalid or never-matching patterns: nothing is
    // removed.
    for (List<String> list :
        Arrays.asList(
            null,
            Collections.<String>emptyList(),
            Arrays.asList("", "  "),
            Arrays.asList("([unclosed", "(?!)", ".*"))) {
      EnvMasker m = new EnvMasker(list);
      assertThat(m.maskEnv("DB_PASSWORD", "s3cr3t")).isEqualTo("****(len=6)");
      assertThat(m.maskEnv("DATABASE_URL", "postgres://u:p@h")).doesNotContain(":p@");
      assertThat(m.maskEnv("NEUTRAL", "AKIAIOSFODNN7EXAMPLE")).isEqualTo("****(len=20)");
    }
    EnvMasker invalid = new EnvMasker(Arrays.asList("([unclosed", repeat('a', 600)));
    assertThat(invalid.rejectedUserPatterns()).isEqualTo(2);
    List<String> many = new ArrayList<String>();
    for (int i = 0; i < EnvMasker.MAX_USER_PATTERNS + 5; i++) {
      many.add("X" + i + "Y");
    }
    assertThat(new EnvMasker(many).userPatternCount()).isEqualTo(EnvMasker.MAX_USER_PATTERNS);
  }

  @Test
  @DisplayName("T-SEC-20 no configuration key turns environment-dump masking off")
  void noKeyDisablesMasking() {
    List<String> commandKeys = new ArrayList<String>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (spec.name().startsWith("commands.env-dump")) {
        commandKeys.add(spec.name());
      }
    }
    assertThat(commandKeys).containsExactly(ConfigKeys.ENV_DUMP_MASK_PATTERNS);
    // The only key is a list of patterns to add: an empty list still masks.
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put(ConfigKeys.ENV_DUMP_MASK_PATTERNS, "[]");
    AgentConfig config = AgentConfig.of(values);
    EnvMasker m = EnvDumpCommand.masker(config);
    assertThat(m.maskEnv("DB_PASSWORD", "s3cr3t")).isEqualTo("****(len=6)");
  }

  @Test
  @DisplayName("T-AG-072/T-SEC-20 the env dump text has masked values, sorted keys, three sections")
  void dumpText() {
    Map<String, String> env = new LinkedHashMap<String, String>();
    env.put("PATH", "/usr/bin:/bin");
    env.put("DB_PASSWORD", "ARGOS_SEED_pw_1");
    env.put("JAVA_TOOL_OPTIONS", "-Dx.password=ARGOS_SEED_pw_2 -Xmx1g");
    Map<String, String> props = new LinkedHashMap<String, String>();
    props.put("java.version", "21");
    props.put("app.secret", "ARGOS_SEED_pw_3");
    props.put("line.separator", "\n");
    List<String> args = Arrays.asList("-Dapi.token=ARGOS_SEED_pw_4", "-Xmx1g");
    CommandOutput out = EnvDumpCommand.dump(masker, env, props, args);
    assertThat(out.contentType()).isEqualTo("text/plain");
    assertThat(out.truncated()).isFalse();
    String text = new String(out.payload(), StandardCharsets.UTF_8);
    assertThat(text).doesNotContain("ARGOS_SEED");
    assertThat(text)
        .contains(
            "# env (3)\nDB_PASSWORD=****(len=15)\nJAVA_TOOL_OPTIONS=-Dx.password=****(len=15)"
                + " -Xmx1g\nPATH=/usr/bin:/bin\n")
        .contains(
            "# properties (3)\napp.secret=****(len=15)\njava.version=21\nline.separator=\\n\n")
        .contains("# args (2)\n-Dapi.token=****(len=15)\n-Xmx1g\n");
    assertThat(EnvDumpCommand.dump(masker, null, props, args).payload())
        .satisfies(
            b -> assertThat(new String(b, StandardCharsets.UTF_8)).contains("# env (unavailable)"));
  }

  @Test
  @DisplayName("T-AG-072 the env dump is cut at 256 KiB by whole lines")
  void dumpCap() {
    Map<String, String> env = new LinkedHashMap<String, String>();
    for (int i = 0; i < 3000; i++) {
      env.put(
          String.format(java.util.Locale.ROOT, "VAR_%05d", i),
          repeat(' ', 60).replace(" ", "v ") + i);
    }
    CommandOutput out =
        EnvDumpCommand.dump(masker, env, Collections.<String, String>emptyMap(), null);
    assertThat(out.truncated()).isTrue();
    assertThat(out.payload().length).isLessThanOrEqualTo(EnvDumpCommand.MAX_BYTES);
    String text = new String(out.payload(), StandardCharsets.UTF_8);
    assertThat(text).endsWith("\n" + EnvDumpCommand.TRUNCATED_LINE + "\n");
  }

  @Test
  @DisplayName("T-AG-072/T-SEC-20 the real JVM's env dump masks a secret system property")
  void realJvm() throws Exception {
    String name = "argos.test.envmasker." + System.nanoTime() + ".password";
    System.setProperty(name, "ARGOS_SEED_real_value");
    try {
      CommandOutput out =
          new EnvDumpCommand(CommandEnv.builder().build())
              .run(Collections.<String, String>emptyMap());
      String text = new String(out.payload(), StandardCharsets.UTF_8);
      assertThat(text).contains(name + "=****(len=21)").doesNotContain("ARGOS_SEED_real_value");
      assertThat(text).contains("# env (").contains("# properties (").contains("# args (");
    } finally {
      System.clearProperty(name);
    }
  }

  private static String repeat(char c, int n) {
    char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
