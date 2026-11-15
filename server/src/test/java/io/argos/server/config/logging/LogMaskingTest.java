/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * The masking rules of the server log (12 §7.1, 17 §11.3, ADR-0010 C-94). The linear-time test over
 * inputs of several megabytes is {@code LogMaskingSlowIT} (src/integrationTest, 18 §2.2).
 */
@Tag("req:SEC-06")
class LogMaskingTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** A token of the agent token format: {@code argos_} + 43 base64url characters. */
  static String agentToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "argos_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String secret() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return "s" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] randomBytes(int n) {
    byte[] bytes = new byte[n];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  private static final String DASHES = "-----";

  /**
   * A PEM armour line ({@code kind} is {@code BEGIN} or {@code END}), assembled at run time, so the
   * source of this class holds no armour that secret scanners report as a private key.
   */
  private static String armour(String kind, String label) {
    return DASHES + kind + " " + label + DASHES;
  }

  /** The base64 lines of a PEM body (64 characters each, as {@code JwtKey.toPem()} writes them). */
  private static List<String> pemLines(byte[] der) {
    return List.of(
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(der)
            .split("\n"));
  }

  /**
   * {@link LogMasking#mask(String)}, checking on every input of this class that masking is
   * idempotent: masking the masked text again changes nothing.
   */
  private static String mask(String text) {
    String once = LogMasking.mask(text);
    assertThat(LogMasking.mask(once)).as("mask(mask(x)) equals mask(x)").isEqualTo(once);
    return once;
  }

  @Test
  @DisplayName("agent tokens become argos_****")
  void agentTokens() {
    String token = agentToken();
    assertThat(token).hasSize(49);
    assertThat(mask("token " + token + " revoked")).isEqualTo("token argos_**** revoked");
    assertThat(mask("[" + token + "]")).isEqualTo("[argos_****]");
    // Short argos_ identifiers (metric names, account names) are not tokens.
    assertThat(mask("argos_ingest_lag_seconds argos_app"))
        .isEqualTo("argos_ingest_lag_seconds argos_app");
  }

  @Test
  @DisplayName(
      "Authorization headers keep their scheme (also in header lists and maps), other Bearer values"
          + " are masked")
  void authorization() {
    String s = secret();
    assertThat(mask("Authorization: Bearer " + s)).isEqualTo("Authorization: Bearer ****");
    assertThat(mask("authorization=Basic " + s + "==, next"))
        .isEqualTo("authorization=Basic ****, next");
    assertThat(mask("{\"Authorization\":\"" + s + "\"}")).isEqualTo("{\"Authorization\":\"****\"}");
    assertThat(mask("sent bearer " + s + " to peer")).isEqualTo("sent bearer **** to peer");
    assertThat(mask("Authorization failed for user x"))
        .isEqualTo("Authorization failed for user x");

    // Header lists and maps: scheme and closing bracket stay, the credential goes.
    String t = secret();
    // Spring's request logging (DispatcherServlet TRACE with log-request-details).
    String list = mask("headers={Authorization:[Bearer " + s + "], host:[h:1]}");
    assertThat(list).isEqualTo("headers={Authorization:[Bearer ****], host:[h:1]}");
    // HttpHeaders / Map<String, List<String>>.toString().
    String map = mask("{Authorization=[Bearer " + s + "], Host=[x]}");
    assertThat(map).isEqualTo("{Authorization=[Bearer ****], Host=[x]}");
    String basic = mask("authorization=[Basic " + s + "==]");
    assertThat(basic).isEqualTo("authorization=[Basic ****]");
    String several = mask("{Authorization=[Bearer " + s + "], Accept=[*/*], X-Trace=[" + t + "]}");
    assertThat(several)
        .isEqualTo("{Authorization=[Bearer ****], Accept=[*/*], X-Trace=[" + t + "]}");
    String parenthesised = mask("Authorization: (Bearer " + s + ")");
    assertThat(parenthesised).isEqualTo("Authorization: (Bearer ****)");
    String noScheme = mask("Authorization:[" + s + "]");
    assertThat(noScheme).isEqualTo("Authorization:[****]");
    assertThat(List.of(list, map, basic, several, parenthesised, noScheme))
        .noneMatch(m -> m.contains(s));
  }

  @Test
  @DisplayName(
      "Authorization headers with several values: every credential is masked, the schemes stay")
  void authorizationSeveralValues() {
    String s1 = secret();
    String s2 = secret();
    String list = mask("{authorization:[Basic " + s1 + ", Basic " + s2 + "]}");
    assertThat(list).isEqualTo("{authorization:[Basic ****, Basic ****]}");
    String map = mask("{Authorization=[Token " + s1 + ", Token " + s2 + "], Host=[x]}");
    assertThat(map).isEqualTo("{Authorization=[Token ****, Token ****], Host=[x]}");
    String plain = mask("Authorization: Basic " + s1 + ", Basic " + s2);
    assertThat(plain).isEqualTo("Authorization: Basic ****, Basic ****");
    String json = mask("{\"Authorization\":[\"Bearer " + s1 + "\",\"Bearer " + s2 + "\"]}");
    assertThat(json).isEqualTo("{\"Authorization\":[\"Bearer ****\",\"Bearer ****\"]}");
    String unclosed = mask("Authorization: [Bearer " + s1 + ", Bearer " + s2);
    assertThat(unclosed).isEqualTo("Authorization: [Bearer ****, Bearer ****");
    // A list that is masked in part is masked entirely.
    String half = mask("{authorization:[Basic ****, Basic " + s2 + "]}");
    assertThat(half).isEqualTo("{authorization:[Basic ****, Basic ****]}");
    // Every value quoted, as Spring's HttpHeaders.toString() prints several values.
    String quoted = mask("[Authorization:\"Basic " + s1 + "\", \"Basic " + s2 + "\", Host:\"x\"]");
    assertThat(quoted).isEqualTo("[Authorization:\"Basic ****\", \"Basic ****\", Host:\"x\"]");
    String quotedToken =
        mask("[Authorization:\"Token " + s1 + "\", \"Token " + s2 + "\", Host:\"x\"]");
    assertThat(quotedToken).isEqualTo("[Authorization:\"Token ****\", \"Token ****\", Host:\"x\"]");
    String quotedPlain = mask("Authorization: \"Basic " + s1 + "\", \"Basic " + s2 + "\"");
    assertThat(quotedPlain).isEqualTo("Authorization: \"Basic ****\", \"Basic ****\"");
    // A list broken over lines inside brackets.
    String lines = mask("Authorization=[Bearer " + s1 + ",\n Basic " + s2 + "]");
    assertThat(lines).isEqualTo("Authorization=[Bearer ****,\n Basic ****]");
    assertThat(
            List.of(
                list, map, plain, json, unclosed, half, quoted, quotedToken, quotedPlain, lines))
        .noneMatch(m -> m.contains(s1) || m.contains(s2));
  }

  @Test
  @DisplayName("Spring HttpHeaders with several Authorization values are masked entirely")
  void springHttpHeaders() {
    String s1 = secret();
    String s2 = secret();
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.AUTHORIZATION, "Basic " + s1);
    headers.add(HttpHeaders.AUTHORIZATION, "Basic " + s2);
    headers.add(HttpHeaders.HOST, "x");
    assertThat(mask(headers.toString()))
        .isEqualTo("[Authorization:\"Basic ****\", \"Basic ****\", Host:\"x\"]");
  }

  @Test
  @DisplayName("already masked Authorization values stay as they are")
  void authorizationAlreadyMasked() {
    for (String masked :
        List.of(
            "Authorization: Bearer ****",
            "{Authorization=[Bearer ****], Host=[x]}",
            "Authorization:[****]",
            "authorization=[Basic ****]",
            "{authorization:[Basic ****, Basic ****]}",
            "{Authorization=[Token ****, Token ****], Host=[x]}",
            "Authorization: Basic ****, Basic ****",
            "Authorization: (Bearer ****)",
            "authorization=Basic ****, next",
            "{\"Authorization\":\"****\"}",
            "[Authorization:\"Basic ****\", \"Basic ****\", Host:\"x\"]",
            "[Authorization:\"Token ****\", \"Token ****\", Host:\"x\"]",
            "Authorization: \"Basic ****\", \"Basic ****\"",
            "Authorization=[Bearer ****,\n Basic ****]")) {
      assertThat(LogMasking.mask(masked)).isEqualTo(masked);
    }
  }

  @Test
  @DisplayName("masking is idempotent where rules meet (mask(mask(x)) equals mask(x))")
  void idempotentAcrossRules() {
    String s = secret();
    String token = agentToken();
    List<String> masked =
        List.of(
            mask("access_token: Bearer " + s),
            mask("Authorization: Bearer " + token),
            mask("Authorization: [Basic " + token + ", Token " + s + "]"),
            mask("client_secret=" + token),
            mask("http://token:" + s + "@clickhouse:8123/?password=" + s),
            mask("Authorization: Digest username=" + s + ", Basic " + s + ";next"));
    assertThat(masked).noneMatch(m -> m.contains(s) || m.contains(token));
  }

  @Test
  @DisplayName("long Authorization values with runs of blanks and quotes are masked in linear time")
  void authorizationLongValues() {
    String s = secret();
    String run = " \"".repeat(100_000);
    String bracketed = "{Authorization=[Basic " + s + run + "x, Basic " + s + run + "]}";
    String result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> mask(bracketed));
    assertThat(result)
        .isEqualTo("{Authorization=[Basic ****, Basic ****" + run + "]}")
        .doesNotContain(s);
  }

  @Test
  @DisplayName("unclosed brackets after Authorization are masked in linear time")
  void authorizationUnclosedBrackets() {
    String s = secret();
    String tail = "Authorization: Basic " + s;
    String spaces = "Authorization:[ ".repeat(32_768);
    String tab = "Authorization: [\t";
    String tabs = tab.repeat(512 * 1024 / tab.length());
    for (String prefix : List.of(spaces, tabs)) {
      String result =
          assertTimeoutPreemptively(Duration.ofSeconds(3), () -> LogMasking.mask(prefix + tail));
      assertThat(result).isEqualTo(prefix + "Authorization: Basic ****");
    }
  }

  @Test
  @DisplayName("passwords in JDBC and HTTP URLs are masked")
  void urls() {
    String s = secret();
    assertThat(mask("jdbc:postgresql://db:5432/argos?user=argos_app&password=" + s + "&ssl=true"))
        .isEqualTo("jdbc:postgresql://db:5432/argos?user=argos_app&password=****&ssl=true");
    assertThat(mask("jdbc:clickhouse://ch:8123/argos;pwd=" + s))
        .isEqualTo("jdbc:clickhouse://ch:8123/argos;pwd=****");
    assertThat(mask("http://argos_writer:" + s + "@clickhouse:8123/"))
        .isEqualTo("http://argos_writer:****@clickhouse:8123/");
    assertThat(mask("http://clickhouse:8123/ping")).isEqualTo("http://clickhouse:8123/ping");
  }

  @Test
  @DisplayName("key=value and key: value secrets are masked, file keys and ordinary words are not")
  void keyValues() {
    String s = secret();
    assertThat(mask("db.password=" + s + " user=argos")).isEqualTo("db.password=**** user=argos");
    assertThat(mask("SPRING_DATASOURCE_PASSWORD=" + s))
        .isEqualTo("SPRING_DATASOURCE_PASSWORD=****");
    assertThat(mask("api_key: " + s)).isEqualTo("api_key: ****");
    assertThat(mask("{\"client_secret\":\"" + s + "\"}")).isEqualTo("{\"client_secret\":\"****\"}");
    assertThat(mask("X-Api-Key=" + s)).isEqualTo("X-Api-Key=****");
    assertThat(mask("argos.metrics.token-file=/run/secrets/metrics_token"))
        .isEqualTo("argos.metrics.token-file=/run/secrets/metrics_token");
    assertThat(mask("argos.postgres.password=****")).isEqualTo("argos.postgres.password=****");
    assertThat(mask("3 tokens: refreshed")).isEqualTo("3 tokens: refreshed");
    assertThat(mask(null)).isNull();
    assertThat(mask("")).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-06e session cookies, the CSRF header and setup tokens are masked (16 §7.1)")
  void sessionCookies() {
    String at = secret();
    String rt = secret();
    String csrf = secret();
    assertThat(mask("Cookie:\"argos_at=" + at + "; argos_rt=" + rt + "; argos_csrf=" + csrf + "\""))
        .isEqualTo("Cookie:\"argos_at=****; argos_rt=****; argos_csrf=****\"");
    assertThat(mask("Cookie:[argos_at=" + at + ", other=1]"))
        .isEqualTo("Cookie:[argos_at=****, other=1]");
    assertThat(
            mask(
                "Set-Cookie: argos_rt="
                    + rt
                    + "; Path=/api/v1/auth/refresh; Max-Age=86400; Secure; HttpOnly"))
        .isEqualTo(
            "Set-Cookie: argos_rt=****; Path=/api/v1/auth/refresh; Max-Age=86400; Secure; HttpOnly");
    assertThat(mask("X-Argos-CSRF:\"" + csrf + "\"")).isEqualTo("X-Argos-CSRF:\"****\"");
    assertThat(mask("X-Argos-Setup-Token: " + csrf)).isEqualTo("X-Argos-Setup-Token: ****");
    assertThat(mask("{\"setup_token\":\"" + csrf + "\"}")).isEqualTo("{\"setup_token\":\"****\"}");
    assertThat(mask("argos_rt_rotations_total 3")).isEqualTo("argos_rt_rotations_total 3");
  }

  @Test
  @DisplayName("T-SEC-06e anything shaped like a JWT is masked (16 §7.1)")
  void jwtShape() {
    String jwt = "eyJhbGciOiJFZERTQSJ9." + secret() + "." + secret();
    assertThat(mask("token was " + jwt + " today")).isEqualTo("token was **** today");
    assertThat(mask("argos_at=" + jwt)).isEqualTo("argos_at=****");
    assertThat(mask("version 1.2.3 of eyJ")).isEqualTo("version 1.2.3 of eyJ");
  }

  @Test
  @DisplayName(
      "T-SEC-06e JWT signing key and master key settings are masked; their file settings, key ids"
          + " and other key names are not (16 §7.1)")
  void signingKeySettings() {
    String key = secret();
    // ARGOS_JWT_KEY as the installer writes it: base64 of a 32-byte seed, padded (16 §10.6).
    String seed = Base64.getEncoder().encodeToString(randomBytes(32));
    assertThat(seed).endsWith("=");
    assertThat(mask("argos.jwt-key=" + key)).isEqualTo("argos.jwt-key=****");
    assertThat(mask("ARGOS_JWT_KEY=" + seed)).isEqualTo("ARGOS_JWT_KEY=****");
    assertThat(mask("argos.master-key=" + key)).isEqualTo("argos.master-key=****");
    assertThat(mask("ARGOS_MASTER_KEY=" + seed)).isEqualTo("ARGOS_MASTER_KEY=****");
    assertThat(mask("argos.jwt-previous-key: " + key)).isEqualTo("argos.jwt-previous-key: ****");
    assertThat(mask("ARGOS_JWT_PREVIOUS_KEY=" + seed)).isEqualTo("ARGOS_JWT_PREVIOUS_KEY=****");
    assertThat(mask("{\"jwt_key\":\"" + key + "\"}")).isEqualTo("{\"jwt_key\":\"****\"}");
    // The component names of a record's toString(), as ArgosProperties prints them.
    assertThat(
            mask(
                "masterKey="
                    + key
                    + ", jwtKeyFile=conf/jwt.key, jwtKey="
                    + key
                    + ", jwtPreviousKey="
                    + seed))
        .isEqualTo("masterKey=****, jwtKeyFile=conf/jwt.key, jwtKey=****, jwtPreviousKey=****");

    // File settings, key ids and the messages of the KEYS start phase stay readable.
    String kid = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(12));
    for (String plain :
        List.of(
            "argos.jwt-key-file=conf/jwt.key",
            "argos.jwt-previous-key-file=/run/secrets/jwt_previous.key",
            "argos.master-key-file=conf/master.key",
            "ARGOS_JWT_KEY_FILE=/data/jwt.key",
            "JwtKey[kid=" + kid + "]",
            "JwtKey[kid=" + kid + ", verify-only]",
            "kid=" + kid,
            "JWT signing with key id " + kid + " (argos.jwt-key-file conf/jwt.key)",
            "argos.jwt-key is not a usable Ed25519 key: the key is neither PEM nor base64",
            "argos.jwt-key-file names a file that cannot be read (/data/conf/jwt.key):"
                + " NoSuchFileException",
            // No suffix as broad as "key" alone.
            "cache key=" + kid + " sort-key: name primary_key=id public-key=" + kid)) {
      assertThat(mask(plain)).as(plain).isEqualTo(plain);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-06e private keys in PEM armour are masked over several lines up to their end line, and"
          + " without one up to the end of the message (16 §7.1)")
  void pemPrivateKeys() {
    List<String> lines = pemLines(randomBytes(96));
    assertThat(lines).hasSize(2);
    String body = String.join("\n", lines);
    String begin = armour("BEGIN", "PRIVATE KEY");
    String end = armour("END", "PRIVATE KEY");
    String masked = begin + LogMasking.MASK + end;

    // conf/jwt.key as JwtKey.toPem() writes it, inside a message with text around it.
    String block = mask("read:\n" + begin + "\n" + body + "\n" + end + "\nnext line");
    assertThat(block).isEqualTo("read:\n" + masked + "\nnext line");
    // Other private key labels and CRLF line ends.
    String rsaBegin = armour("BEGIN", "RSA PRIVATE KEY");
    String rsaEnd = armour("END", "RSA PRIVATE KEY");
    String rsa = mask(rsaBegin + "\r\n" + String.join("\r\n", lines) + "\r\n" + rsaEnd);
    assertThat(rsa).isEqualTo(rsaBegin + LogMasking.MASK + rsaEnd);
    String encryptedBegin = armour("BEGIN", "ENCRYPTED PRIVATE KEY");
    String encryptedEnd = armour("END", "ENCRYPTED PRIVATE KEY");
    String encrypted = mask(encryptedBegin + "\n" + body + "\n" + encryptedEnd);
    assertThat(encrypted).isEqualTo(encryptedBegin + LogMasking.MASK + encryptedEnd);
    // Newlines escaped, as an .env file or a JSON string carries the block.
    String escaped =
        mask("{\"key\":\"" + begin + "\\n" + String.join("\\n", lines) + "\\n" + end + "\"}");
    assertThat(escaped).isEqualTo("{\"key\":\"" + masked + "\"}");
    // Two blocks in one message: each is masked, the text between them stays.
    String two = mask(begin + body + end + " and " + begin + "\n" + body + "\n" + end);
    assertThat(two).isEqualTo(masked + " and " + masked);
    // The block as the value of ARGOS_JWT_KEY (16 §7.1): rule 5 takes the first word of what the
    // PEM rule left, the body is gone.
    String setting = mask("ARGOS_JWT_KEY=" + begin + "\n" + body + "\n" + end);
    assertThat(setting).startsWith("ARGOS_JWT_KEY=****").endsWith(LogMasking.MASK + end);
    // Without the matching end line (a message cut short, an end line of another label):
    // everything after the begin line is masked.
    String cut = mask("loaded " + begin + "\n" + body + "\nat line 3");
    assertThat(cut).isEqualTo("loaded " + begin + LogMasking.MASK);
    String otherEnd = mask(begin + "\n" + body + "\n" + rsaEnd + "\ntail");
    assertThat(otherEnd).isEqualTo(begin + LogMasking.MASK);
    assertThat(List.of(block, rsa, encrypted, escaped, two, setting, cut, otherEnd))
        .noneMatch(m -> lines.stream().anyMatch(m::contains));

    // Public keys and certificates are not secrets and stay as they are.
    byte[] fixed = new byte[90];
    for (int i = 0; i < fixed.length; i++) {
      fixed[i] = (byte) i;
    }
    String publicBody = String.join("\n", pemLines(fixed));
    for (String label : List.of("PUBLIC KEY", "CERTIFICATE")) {
      String pem = armour("BEGIN", label) + "\n" + publicBody + "\n" + armour("END", label);
      assertThat(mask(pem)).as(label).isEqualTo(pem);
    }
  }
}
