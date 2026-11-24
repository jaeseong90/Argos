/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.transport.CheckProbe;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --check} / {@code --version} (10 §13.3, F-A27, T-AG-068a): table and JSON forms, exit
 * codes 0/1/2, token masking, configuration, connection, TLS, hello and clock verdicts, JDK and
 * class path compatibility. The session round is a fake {@link ConnectionChecks.Prober}; the real
 * one runs in agent-testing {@code check.CheckCliTest}.
 */
@Tag("req:F-A27")
class CheckCommandTest {
  /** A token of the 16 §2.1 format (test value, not a secret). */
  static final String TOKEN = "argos_" + repeat('T', 43);

  @TempDir File temp;

  // --- helpers -----------------------------------------------------------------------------------

  static String repeat(char c, int n) {
    char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  /** The output of one run and its exit code. */
  static final class Run {
    final int exit;
    final String out;

    Run(int exit, String out) {
      this.exit = exit;
      this.out = out;
    }
  }

  CheckCommand.Environment env(Map<String, String> properties, final CheckProbe.Result result) {
    CheckCommand.Environment env = new CheckCommand.Environment();
    env.javaVersion = "21.0.5";
    env.javaVendor = "Eclipse Adoptium";
    env.vmName = "OpenJDK 64-Bit Server VM";
    env.systemProperties = properties;
    env.environment = Collections.<String, String>emptyMap();
    env.hostName = "app-host";
    env.nowMillis = 1_800_000_000_000L;
    env.tmpDir = temp.getPath();
    env.resolver =
        new ConnectionChecks.Resolver() {
          @Override
          public List<String> resolve(String host) throws UnknownHostException {
            if (host.equals("nowhere.invalid")) {
              throw new UnknownHostException(host);
            }
            return Collections.singletonList("10.0.0.5");
          }
        };
    env.prober =
        new ConnectionChecks.Prober() {
          @Override
          public CheckProbe.Result probe(AgentConfig config, String instanceName) {
            result.instanceName = instanceName;
            return result;
          }
        };
    env.permissions =
        new ConfigChecks.PermissionReader() {
          @Override
          public String posixPermissions(File file) {
            return "rw-------";
          }
        };
    return env;
  }

  Map<String, String> goodProperties() throws IOException {
    File tokenFile = new File(temp, "token");
    java.nio.file.Files.write(tokenFile.toPath(), TOKEN.getBytes(StandardCharsets.UTF_8));
    Map<String, String> p = new LinkedHashMap<String, String>();
    p.put("argos.server.address", "collector.example:9100");
    p.put("argos.server.tls", "false");
    p.put("argos.server.token-file", tokenFile.getPath());
    p.put("argos.app.name", "shop");
    p.put("argos.app.env", "prod");
    p.put("argos.log.dir", new File(temp, "logs").getPath());
    return p;
  }

  static CheckProbe.Result accepted(long serverOffsetMillis) {
    CheckProbe.Result result = new CheckProbe.Result();
    result.serverAnswered = true;
    result.helloSentAtMs = 1_800_000_000_000L;
    result.helloReceivedAtMs = 1_800_000_000_010L;
    result.hello =
        ServerHello.newBuilder()
            .setAccepted(true)
            .setAppId(3)
            .setInstanceId(41)
            .setServerVersion("1.0.0")
            .setProtocolVersion(1)
            .setMinAgentVersion("0.8.0")
            .setMaxAgentVersion("1.0.0")
            .setServerTimeMs(1_800_000_000_005L + serverOffsetMillis)
            .build();
    result.byeSent = true;
    result.statusCode = "OK";
    return result;
  }

  static Run run(String[] args, CheckCommand.Environment env) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    env.out = new PrintStream(bytes, true);
    int exit = CheckCommand.run(args, null, env);
    return new Run(
        exit, new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
  }

  static String line(String out, String check) {
    for (String line : out.split("\n", -1)) {
      if (line.matches("^\\s*\\d+\\s+" + check + "\\s+.*")) {
        return line;
      }
    }
    throw new AssertionError("no row " + check + " in\n" + out);
  }

  // --- tests -------------------------------------------------------------------------------------

  @Test
  @DisplayName("U-4: Java 8 needs update 252 or later (TLS ALPN); Java 9+ is supported")
  void jdkVersions() {
    assertThat(CheckCommand.jdkSupported("1.8.0_252")).isTrue();
    assertThat(CheckCommand.jdkSupported("1.8.0_504")).isTrue();
    assertThat(CheckCommand.jdkSupported("1.8.0_251")).isFalse();
    assertThat(CheckCommand.jdkSupported("1.8.0_202-b08")).isFalse();
    assertThat(CheckCommand.jdkSupported("1.8.0")).isFalse();
    assertThat(CheckCommand.jdkSupported("1.7.0_80")).isFalse();
    assertThat(CheckCommand.jdkSupported("11.0.32.1")).isTrue();
    assertThat(CheckCommand.jdkSupported("25")).isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-068a table: every item in 10 §13.3 order, PASS/WARN only -> exit 0, side effect line")
  void tableAllPass() throws IOException {
    Run run = run(new String[] {"--check"}, env(goodProperties(), accepted(5)));
    assertThat(run.exit).as(run.out).isZero();
    String[] lines = run.out.split("\n", -1);
    assertThat(lines[0]).startsWith("Argos agent unknown (commit unknown, protocol unknown)");
    assertThat(lines[1]).matches("^ # +CHECK +RESULT +DETAIL$");
    List<String> order = new ArrayList<String>();
    for (String l : lines) {
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile(
                  "^\\s*(\\d+)\\s+([a-z-]+)\\s+(PASS|WARN|FAIL|SKIP|INFO)\\s")
              .matcher(l);
      if (m.find()) {
        order.add(m.group(1) + " " + m.group(2));
      }
    }
    assertThat(order)
        .containsExactly(
            "1 config",
            "2 token",
            "3 dns",
            "3 tcp",
            "3 tls",
            "4 hello",
            "5 clock",
            "6 jdk",
            "7 classpath",
            "8 log-dir",
            "8 token-file");
    assertThat(line(run.out, "config")).contains("PASS").contains("app 'shop', env 'prod'");
    assertThat(line(run.out, "token")).contains("PASS").contains("argos_****");
    assertThat(line(run.out, "dns")).contains("PASS").contains("collector.example -> 10.0.0.5");
    assertThat(line(run.out, "tcp")).contains("PASS").contains("collector.example:9100");
    assertThat(line(run.out, "tls")).contains("WARN").contains("plaintext");
    assertThat(line(run.out, "hello"))
        .contains("PASS")
        .contains("server 1.0.0 (protocol 1, accepts agents 0.8.0 to 1.0.0)")
        .contains("instance 'app-host-check'");
    assertThat(line(run.out, "clock")).contains("PASS");
    assertThat(line(run.out, "jdk")).contains("PASS").contains("21.0.5 (Eclipse Adoptium)");
    assertThat(line(run.out, "classpath")).contains("SKIP");
    assertThat(line(run.out, "log-dir")).contains("PASS").contains("creates it (0700)");
    assertThat(line(run.out, "token-file")).contains("PASS").contains("rw------- (owner only)");
    assertThat(run.out)
        .contains("Result: WARN (")
        .contains("exit code 0")
        .contains(
            "Note: the hello check opened a session as instance 'app-host-check' and ended"
                + " it with AgentBye");
    assertThat(run.out).doesNotContain(TOKEN).doesNotContain(TOKEN.substring(6));
    assertThat(run.out)
        .contains("Configuration (keys not at their default):")
        .contains("  server.address = collector.example:9100 (sysprop)")
        .contains("  app.env = prod (sysprop)");
  }

  @Test
  @DisplayName("T-AG-068a exit codes: 0 pass/warn, 1 fail, 2 usage; --version prints one line")
  void exitCodes() throws IOException {
    assertThat(run(new String[] {"--version"}, env(goodProperties(), accepted(0))).out)
        .isEqualTo("Argos agent unknown (commit unknown, protocol unknown)\n");
    assertThat(run(new String[] {"--check"}, env(goodProperties(), accepted(0))).exit).isZero();
    CheckCommand.Environment old = env(goodProperties(), accepted(0));
    old.javaVersion = "1.8.0_201";
    old.javaVendor = "Oracle Corporation";
    Run oldJdk = run(new String[] {"--check"}, old);
    assertThat(oldJdk.exit).isEqualTo(1);
    assertThat(line(oldJdk.out, "jdk")).contains("FAIL").contains("1.8.0_201");
    for (String[] usage :
        new String[][] {
          {},
          {"--bogus"},
          {"--check", "--bogus"},
          {"--check", "--config"},
          {"--check", "--config", "--json"},
          {"--check", "--classpath"},
          {"--check", "--classpath", "--json"},
          {"--check", "--json", "--json"},
          {"--version", "--json"}
        }) {
      Run r = run(usage, env(goodProperties(), accepted(0)));
      assertThat(r.exit).as(Arrays.toString(usage)).isEqualTo(2);
      assertThat(r.out).contains("usage: java -jar argos-agent.jar --check [--config");
    }
    assertThat(run(new String[] {"--help"}, env(goodProperties(), accepted(0))).exit).isZero();
  }

  @Test
  @DisplayName("T-AG-068a --json: one object with agent, result, exit_code, summary, checks")
  void json() throws IOException {
    CheckProbe.Result result = accepted(2_000);
    result.statusDescription = "echo " + TOKEN;
    Run run = run(new String[] {"--check", "--json"}, env(goodProperties(), result));
    assertThat(run.exit).isZero();
    Object parsed = MiniJson.parse(run.out.trim());
    assertThat(parsed).isInstanceOf(Map.class);
    Map<?, ?> root = (Map<?, ?>) parsed;
    assertThat(keys(root))
        .containsExactly(
            "agent", "result", "exit_code", "summary", "checks", "side_effects", "configuration");
    assertThat(keys(root.get("agent"))).containsExactly("version", "commit", "protocol");
    assertThat(root.get("result")).isEqualTo("WARN");
    assertThat(root.get("exit_code")).isEqualTo(0L);
    assertThat(keys(root.get("summary"))).containsExactly("pass", "warn", "fail", "skip", "info");
    List<?> checks = (List<?>) root.get("checks");
    assertThat(checks).hasSize(11);
    for (Object check : checks) {
      assertThat(keys(check)).containsExactly("item", "check", "status", "detail", "notes");
    }
    Map<?, ?> clock = (Map<?, ?>) checks.get(6);
    assertThat(clock.get("check")).isEqualTo("clock");
    assertThat(clock.get("status")).isEqualTo("WARN");
    assertThat((String) clock.get("detail")).startsWith("offset +2000 ms");
    assertThat((List<?>) root.get("side_effects")).hasSize(1);
    assertThat(run.out).doesNotContain(TOKEN.substring(6));
  }

  @Test
  @DisplayName(
      "T-AG-068a token masking: a plaintext token is WARN and never printed, even inside a"
          + " server message")
  void tokenMasking() throws IOException {
    Map<String, String> p = goodProperties();
    p.remove("argos.server.token-file");
    p.put("argos.server.token", TOKEN);
    CheckProbe.Result result = new CheckProbe.Result();
    result.serverAnswered = true;
    result.statusCode = "UNAUTHENTICATED";
    result.statusDescription = "bad token " + TOKEN + " for app";
    result.rejectReasonTrailer = "TOKEN_INVALID";
    Run run = run(new String[] {"--check"}, env(p, result));
    assertThat(run.exit).isEqualTo(1);
    assertThat(line(run.out, "token")).contains("WARN").contains("plaintext");
    assertThat(line(run.out, "hello")).contains("FAIL").contains("TOKEN_INVALID");
    assertThat(run.out).contains("bad token argos_**** for app");
    assertThat(run.out).doesNotContain(TOKEN).doesNotContain(TOKEN.substring(6));
    assertThat(run.out).contains("  server.token = argos_**** (sysprop)");
    Run json = run(new String[] {"--check", "--json"}, env(p, result));
    assertThat(json.out).doesNotContain(TOKEN.substring(6));
  }

  @Test
  @DisplayName(
      "T-AG-068a a token that is not argos_ plus 43 characters is FAIL; the output is plain ASCII"
          + " (System.out uses the platform encoding)")
  void malformedToken() throws IOException {
    Map<String, String> p = goodProperties();
    String shortToken = "argos_" + repeat('S', 32);
    java.nio.file.Files.write(
        new File(p.get("argos.server.token-file")).toPath(),
        shortToken.getBytes(StandardCharsets.UTF_8));
    Run run = run(new String[] {"--check"}, env(p, accepted(0)));
    assertThat(run.exit).isEqualTo(1);
    assertThat(line(run.out, "token")).contains("FAIL").contains("argos_ plus 43 characters");
    assertThat(run.out).doesNotContain(repeat('S', 32));
    String withoutPaths = run.out.replace(temp.getPath(), "<temp>");
    for (int i = 0; i < withoutPaths.length(); i++) {
      char c = withoutPaths.charAt(i);
      assertThat(c == '\n' || (c >= 0x20 && c < 0x7f))
          .as("character U+%04X at %d", (int) c, i)
          .isTrue();
    }
  }

  @Test
  @DisplayName("T-AG-068a configuration: missing server.address and no token FAIL, the rest SKIP")
  void missingRequired() {
    Map<String, String> p = new LinkedHashMap<String, String>();
    p.put("argos.app.name", "shop");
    p.put("argos.unknown.key", "1");
    p.put("argos.log.dir", new File(temp, "logs").getPath());
    Run run = run(new String[] {"--check"}, env(p, accepted(0)));
    assertThat(run.exit).isEqualTo(1);
    assertThat(line(run.out, "config")).contains("FAIL").contains("1 required key(s) missing");
    assertThat(run.out)
        .contains("server.address is not set")
        .contains("unknown configuration key")
        .contains("app.env is not set; using 'default'");
    assertThat(line(run.out, "token")).contains("FAIL").contains("no token");
    assertThat(line(run.out, "dns")).contains("SKIP");
    assertThat(line(run.out, "hello")).contains("SKIP");
    assertThat(line(run.out, "token-file")).contains("SKIP");
    assertThat(run.out).doesNotContain("Note:");
  }

  @Test
  @DisplayName("T-AG-068a --config with a missing file is FAIL; app.env missing alone is WARN")
  void configFile() throws IOException {
    Run missing =
        run(
            new String[] {"--check", "--config", new File(temp, "nope.yml").getPath()},
            env(goodProperties(), accepted(0)));
    assertThat(line(missing.out, "config")).contains("FAIL").contains("not found");
    File yml = new File(temp, "argos-agent.yml");
    java.nio.file.Files.write(
        yml.toPath(), "app:\n  name: from-yml\n".getBytes(StandardCharsets.UTF_8));
    Map<String, String> p = goodProperties();
    p.remove("argos.app.name");
    p.remove("argos.app.env");
    Run run = run(new String[] {"--check", "--config", yml.getPath()}, env(p, accepted(0)));
    assertThat(line(run.out, "config")).contains("WARN").contains(yml.getPath());
    assertThat(run.out).contains("app.env is not set; using 'default'");
    assertThat(run.exit).isZero();
  }

  @Test
  @DisplayName("T-AG-068a connection: DNS failure FAIL; refused TCP FAIL; both exit 1")
  void connectionFailures() throws IOException {
    Map<String, String> p = goodProperties();
    p.put("argos.server.address", "nowhere.invalid:9100");
    Run dns = run(new String[] {"--check"}, env(p, accepted(0)));
    assertThat(dns.exit).isEqualTo(1);
    assertThat(line(dns.out, "dns")).contains("FAIL").contains("cannot resolve nowhere.invalid");
    assertThat(line(dns.out, "tcp")).contains("SKIP");

    CheckProbe.Result refused = new CheckProbe.Result();
    refused.statusCode = "UNAVAILABLE";
    refused.statusDescription = "io exception";
    refused.cause = new ConnectException("Connection refused: /127.0.0.1:1");
    Map<String, String> ip = goodProperties();
    ip.put("argos.server.address", "127.0.0.1:1");
    Run tcp = run(new String[] {"--check"}, env(ip, refused));
    assertThat(tcp.exit).isEqualTo(1);
    assertThat(line(tcp.out, "dns")).contains("PASS").contains("IP address");
    assertThat(line(tcp.out, "tcp")).contains("FAIL").contains("connection refused");
    assertThat(line(tcp.out, "tls")).contains("SKIP");
    assertThat(line(tcp.out, "hello")).contains("SKIP");
    assertThat(line(tcp.out, "clock")).contains("SKIP");
  }

  @Test
  @DisplayName("T-AG-068a TLS failures are classified: host name, trust, expiry, not TLS")
  void tlsClassification() {
    ConnectionChecks.Failure host =
        ConnectionChecks.classify(
            "UNAVAILABLE",
            "ssl exception",
            new SSLHandshakeException(
                "No subject alternative names matching IP address 127.0.0.1 found"));
    assertThat(host.stage).isEqualTo(ConnectionChecks.Stage.TLS);
    assertThat(host.message).contains("host name");
    SSLHandshakeException pkix =
        new SSLHandshakeException(
            "PKIX path building failed: unable to find valid certification path");
    pkix.initCause(new CertificateException("certification path"));
    assertThat(ConnectionChecks.classify("UNAVAILABLE", null, pkix).message)
        .contains("not trusted")
        .contains("server.tls.ca-file");
    SSLHandshakeException expired = new SSLHandshakeException("validity check failed");
    expired.initCause(new java.security.cert.CertificateExpiredException("NotAfter: x"));
    assertThat(ConnectionChecks.classify("UNAVAILABLE", null, expired).message).contains("expired");
    assertThat(
            ConnectionChecks.classify(
                    "UNAVAILABLE",
                    null,
                    new javax.net.ssl.SSLException("not an SSL/TLS record: 00"))
                .message)
        .contains("does not speak TLS");
    assertThat(
            ConnectionChecks.classify(
                    "UNAVAILABLE", null, new UnknownHostException("collector.invalid"))
                .stage)
        .isEqualTo(ConnectionChecks.Stage.DNS);
    ConnectionChecks.Failure timeout =
        ConnectionChecks.classify(
            "UNAVAILABLE", null, new ConnectException("connection timed out: /10.0.0.1:9100"));
    assertThat(timeout.stage).isEqualTo(ConnectionChecks.Stage.TCP);
    assertThat(timeout.message).contains("timed out after 5 s");
  }

  @Test
  @DisplayName("T-AG-068a TLS expiry: expired FAIL, within 30 days WARN, later PASS")
  void tlsExpiry() {
    long now = 1_800_000_000_000L;
    long day = ConnectionChecks.DAY_MILLIS;
    assertThat(ConnectionChecks.expiryStatus(now - 10 * day, now + 90 * day, now))
        .isEqualTo(CheckRow.Status.PASS);
    assertThat(ConnectionChecks.expiryStatus(now - 10 * day, now + 30 * day, now))
        .isEqualTo(CheckRow.Status.WARN);
    assertThat(ConnectionChecks.expiryStatus(now - 10 * day, now + 2 * day, now))
        .isEqualTo(CheckRow.Status.WARN);
    assertThat(ConnectionChecks.expiryStatus(now - 10 * day, now - 1, now))
        .isEqualTo(CheckRow.Status.FAIL);
    assertThat(ConnectionChecks.expiryStatus(now + day, now + 90 * day, now))
        .isEqualTo(CheckRow.Status.FAIL);
    assertThat(ConnectionChecks.date(now)).isEqualTo("2027-01-15");
  }

  @Test
  @DisplayName(
      "T-AG-068a insecure-skip-verify is '[WARN] TLS verification disabled', exit code 0 (16 §2)")
  void skipVerify() throws IOException {
    Map<String, String> p = goodProperties();
    p.put("argos.server.tls", "true");
    p.put("argos.server.tls.insecure-skip-verify", "true");
    CheckProbe.Result result = accepted(0);
    result.security = "TLSv1.3";
    Run run = run(new String[] {"--check"}, env(p, result));
    assertThat(line(run.out, "tls")).contains("WARN").contains("TLS verification disabled");
    assertThat(run.exit).isZero();
    p.put("argos.server.tls.insecure-skip-verify", "false");
    Run verified = run(new String[] {"--check"}, env(p, result));
    assertThat(line(verified.out, "tls"))
        .contains("PASS")
        .contains("TLSv1.3, chain (JDK trust store) and host name verified");
  }

  @Test
  @DisplayName("T-AG-068a hello refusals: reason and advice; pending approval FAIL; clock WARN")
  void helloRefusals() {
    CheckProbe.Result pending = new CheckProbe.Result();
    pending.serverAnswered = true;
    pending.statusCode = "FAILED_PRECONDITION";
    pending.hello =
        ServerHello.newBuilder()
            .setAccepted(false)
            .setRejectReason(RejectReason.APP_NOT_REGISTERED)
            .setRejectMessage("pending approval")
            .setServerVersion("1.0.0")
            .setProtocolVersion(1)
            .build();
    pending.instanceName = "h-check";
    CheckRow row = ConnectionChecks.helloRow(pending, true);
    assertThat(row.status).isEqualTo(CheckRow.Status.FAIL);
    assertThat(row.detail).contains("APP_NOT_REGISTERED").contains("approve");
    assertThat(row.notes).contains("server message: pending approval");
    CheckProbe.Result skewed = accepted(-1_500);
    assertThat(ConnectionChecks.clockRow(skewed).status).isEqualTo(CheckRow.Status.WARN);
    assertThat(ConnectionChecks.clockRow(skewed).detail).startsWith("offset -1500 ms");
    assertThat(ConnectionChecks.clockOffset(2_000, 1_000, 1_010)).isEqualTo(995);
  }

  @Test
  @DisplayName("T-AG-068a JDK verdicts: vendor and line against 01 §8")
  void jdkRows() {
    assertThat(JdkCheck.row("17.0.12", "Amazon.com Inc.", "OpenJDK").status)
        .isEqualTo(CheckRow.Status.PASS);
    assertThat(JdkCheck.row("1.8.0_412", "Azul Systems, Inc.", "OpenJDK").status)
        .isEqualTo(CheckRow.Status.PASS);
    assertThat(JdkCheck.row("21.0.4", "BellSoft", "OpenJDK").status)
        .isEqualTo(CheckRow.Status.WARN);
    assertThat(JdkCheck.row("21.0.4", "IBM Corporation", "Eclipse OpenJ9 VM").detail)
        .contains("OpenJ9");
    assertThat(JdkCheck.row("22.0.2", "Eclipse Adoptium", "OpenJDK").status)
        .isEqualTo(CheckRow.Status.WARN);
    assertThat(JdkCheck.row("1.8.0_242", "AdoptOpenJDK", "OpenJDK").status)
        .isEqualTo(CheckRow.Status.FAIL);
    assertThat(JdkCheck.feature("1.8.0_412")).isEqualTo(8);
    assertThat(JdkCheck.feature("25")).isEqualTo(25);
  }

  @Test
  @DisplayName("T-AG-068a log directory and token file permissions (item 8)")
  void item8() throws IOException {
    Map<String, String> p = goodProperties();
    File plainFile = new File(temp, "not-a-dir");
    java.nio.file.Files.write(plainFile.toPath(), new byte[] {1});
    p.put("argos.log.dir", plainFile.getPath());
    CheckCommand.Environment env = env(p, accepted(0));
    env.permissions =
        new ConfigChecks.PermissionReader() {
          @Override
          public String posixPermissions(File file) {
            return "rw-r--r--";
          }
        };
    Run run = run(new String[] {"--check"}, env);
    assertThat(line(run.out, "log-dir")).contains("FAIL").contains("is not a directory");
    assertThat(line(run.out, "token-file"))
        .contains("WARN")
        .contains("rw-r--r--")
        .contains("chmod 600");
    env.permissions =
        new ConfigChecks.PermissionReader() {
          @Override
          public String posixPermissions(File file) {
            return null;
          }
        };
    p.put("argos.log.dir", temp.getPath());
    Run nonPosix = run(new String[] {"--check"}, env);
    assertThat(line(nonPosix.out, "log-dir")).contains("PASS").contains("is writable");
    assertThat(line(nonPosix.out, "token-file")).contains("INFO").contains("no POSIX permissions");
  }

  @Test
  @DisplayName(
      "T-AG-068a --classpath: Boot jar libraries against the compatibility table (supported,"
          + " unverified, unsupported)")
  void classpath() throws IOException {
    File boot = new File(temp, "shop.jar");
    writeJar(
        boot,
        "Implementation-Version: 1.0\n",
        "BOOT-INF/lib/spring-boot-3.5.16.jar",
        "BOOT-INF/lib/spring-core-6.2.19.jar",
        "BOOT-INF/lib/tomcat-embed-core-10.1.55.jar",
        "BOOT-INF/lib/HikariCP-6.3.3.jar",
        "BOOT-INF/lib/okhttp-5.0.0.jar",
        "BOOT-INF/lib/postgresql-42.7.11.jar",
        "BOOT-INF/lib/unrelated-1.0.jar");
    File lib = new File(temp, "tomcat/lib");
    assertThat(lib.mkdirs()).isTrue();
    writeJar(new File(lib, "catalina.jar"), "Implementation-Version: 8.0.53\n");
    File old = new File(temp, "old-boot.jar");
    writeJar(old, "Spring-Boot-Version: 2.6.15\n");
    Run run =
        run(
            new String[] {
              "--check", "--classpath", boot.getPath(), lib.getPath() + File.pathSeparator + old
            },
            env(goodProperties(), accepted(0)));
    assertRow(run.out, "PASS", "Spring Boot 3.5.16 (shop.jar): supported");
    assertRow(run.out, "PASS", "Spring Framework 6.2.19 (shop.jar): supported");
    assertRow(run.out, "PASS", "Tomcat 10.1.55 (shop.jar): supported");
    assertRow(run.out, "PASS", "Servlet API 6.0 (from Tomcat 10.1.55, shop.jar): supported");
    assertRow(run.out, "PASS", "HikariCP 6.3.3 (shop.jar): supported");
    assertRow(run.out, "PASS", "PostgreSQL JDBC 42.7.11 (shop.jar): supported");
    assertRow(run.out, "WARN", "OkHttp 5.0.0 (shop.jar): unverified");
    assertRow(run.out, "FAIL", "Tomcat 8.0.53 (lib/catalina.jar): unsupported");
    assertRow(run.out, "FAIL", "Spring Boot 2.6.15 (old-boot.jar manifest): unsupported");
    assertThat(run.out).doesNotContain("unrelated");
    assertThat(run.exit).isEqualTo(1);
    CheckCommand.Environment jdk25 = env(goodProperties(), accepted(0));
    jdk25.javaVersion = "25.0.1";
    File boot27 = new File(temp, "boot27.jar");
    writeJar(boot27, "Implementation-Version: 1.0\n", "BOOT-INF/lib/spring-boot-2.7.18.jar");
    Run on25 = run(new String[] {"--check", "--classpath", boot27.getPath()}, jdk25);
    assertRow(on25.out, "WARN", "Spring Boot 2.7.18 (boot27.jar): unverified on JDK 25");
    Run missing =
        run(
            new String[] {"--check", "--classpath", new File(temp, "none.jar").getPath()},
            env(goodProperties(), accepted(0)));
    assertThat(missing.out).contains("does not exist");
  }

  @Test
  @DisplayName("compatibility table: resource parses; version lines, ranges and '<' bounds")
  void compatibilityTable() throws IOException {
    CompatibilityTable table = CompatibilityTable.load();
    assertThat(table.components()).hasSizeGreaterThan(20);
    CompatibilityTable.Component okhttp = table.find("okhttp");
    assertThat(okhttp.verdict("3.14.9")).isEqualTo(CompatibilityTable.Verdict.SUPPORTED);
    assertThat(okhttp.verdict("4.12.0")).isEqualTo(CompatibilityTable.Verdict.SUPPORTED);
    assertThat(okhttp.verdict("3.9.1")).isEqualTo(CompatibilityTable.Verdict.UNSUPPORTED);
    assertThat(okhttp.verdict("5.1.0")).isEqualTo(CompatibilityTable.Verdict.UNVERIFIED);
    assertThat(table.find("ojdbc11").verdict("23.7.0.25.01"))
        .isEqualTo(CompatibilityTable.Verdict.SUPPORTED);
    assertThat(table.find("tibero7-jdbc").verdict(null))
        .isEqualTo(CompatibilityTable.Verdict.UNVERIFIED);
    assertThat(table.find("spring-batch-core").verdict("6.0.0"))
        .isEqualTo(CompatibilityTable.Verdict.UNSUPPORTED);
    assertThat(table.find("tomcat-embed-core").verdict("8.5.100"))
        .isEqualTo(CompatibilityTable.Verdict.UNVERIFIED);
    assertThat(table.find("mssql-jdbc").verdict("12.10.2.jre11"))
        .isEqualTo(CompatibilityTable.Verdict.SUPPORTED);
    assertThat(CompatibilityTable.parse("6.6.53.Final")).containsExactly(6, 6, 53);
    assertThat(ClasspathCheck.fromFileName("HikariCP-java7-2.4.13.jar", "x").artifact)
        .isEqualTo("HikariCP-java7");
    assertThat(ClasspathCheck.fromFileName("catalina.jar", "x").version).isNull();
  }

  @Test
  @DisplayName("the instance name of the hello check is <app.instance or host>-check")
  void instanceName() throws IOException {
    Map<String, String> p = goodProperties();
    p.put("argos.app.instance", "shop-1");
    CheckProbe.Result result = accepted(0);
    run(new String[] {"--check"}, env(p, result));
    assertThat(result.instanceName).isEqualTo("shop-1-check");
  }

  static List<String> keys(Object map) {
    List<String> out = new ArrayList<String>();
    for (Object key : ((Map<?, ?>) map).keySet()) {
      out.add((String) key);
    }
    return out;
  }

  /** A row of {@code out} with {@code status} whose detail contains {@code text}. */
  static void assertRow(String out, String status, String text) {
    for (String line : out.split("\n", -1)) {
      if (line.contains(text)) {
        assertThat(line).as(out).matches("^\\s*\\d+\\s+classpath\\s+" + status + "\\s+.*");
        return;
      }
    }
    throw new AssertionError("no row '" + text + "' in\n" + out);
  }

  static void writeJar(File file, String manifest, String... entries) throws IOException {
    java.util.jar.Manifest m =
        new java.util.jar.Manifest(
            new java.io.ByteArrayInputStream(
                ("Manifest-Version: 1.0\n" + manifest).getBytes(StandardCharsets.UTF_8)));
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(new java.io.FileOutputStream(file), m)) {
      for (String entry : entries) {
        out.putNextEntry(new java.util.jar.JarEntry(entry));
        out.write(new byte[] {0});
        out.closeEntry();
      }
    }
  }

  /** A minimal JSON reader for the schema test (objects, arrays, strings, integers, booleans). */
  static final class MiniJson {
    private final String text;
    private int pos;

    private MiniJson(String text) {
      this.text = text;
    }

    static Object parse(String text) {
      MiniJson json = new MiniJson(text);
      Object value = json.value();
      if (json.pos != text.length()) {
        throw new IllegalArgumentException("trailing characters at " + json.pos);
      }
      return value;
    }

    private Object value() {
      char c = text.charAt(pos);
      if (c == '{') {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++;
        if (text.charAt(pos) == '}') {
          pos++;
          return map;
        }
        while (true) {
          String key = (String) value();
          expect(':');
          map.put(key, value());
          if (text.charAt(pos) == ',') {
            pos++;
          } else {
            expect('}');
            return map;
          }
        }
      }
      if (c == '[') {
        List<Object> list = new ArrayList<Object>();
        pos++;
        if (text.charAt(pos) == ']') {
          pos++;
          return list;
        }
        while (true) {
          list.add(value());
          if (text.charAt(pos) == ',') {
            pos++;
          } else {
            expect(']');
            return list;
          }
        }
      }
      if (c == '"') {
        StringBuilder b = new StringBuilder();
        pos++;
        while (text.charAt(pos) != '"') {
          char ch = text.charAt(pos++);
          if (ch == '\\') {
            char e = text.charAt(pos++);
            if (e == 'u') {
              b.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
              pos += 4;
            } else {
              b.append(e == 'n' ? '\n' : e);
            }
          } else {
            if (ch < 0x20) {
              throw new IllegalArgumentException("control character in string");
            }
            b.append(ch);
          }
        }
        pos++;
        return b.toString();
      }
      if (text.startsWith("true", pos)) {
        pos += 4;
        return true;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return false;
      }
      int start = pos;
      while (pos < text.length()
          && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '-')) {
        pos++;
      }
      if (start == pos) {
        throw new IllegalArgumentException("unexpected '" + c + "' at " + pos);
      }
      return Long.valueOf(text.substring(start, pos));
    }

    private void expect(char c) {
      if (text.charAt(pos) != c) {
        throw new IllegalArgumentException("expected '" + c + "' at " + pos);
      }
      pos++;
    }
  }
}
