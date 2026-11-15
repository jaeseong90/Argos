/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.auth.Role;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The access token (16 §7.1, 12 §7.2, SEC-40, SEC-41): what is issued, and every token the strict
 * parser must refuse. Forged tokens are built by hand here, with fresh keys per test (no key or
 * token is a literal).
 */
@Tag("req:SEC-40")
@Tag("req:SEC-41")
class AccessTokenServiceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");

  private JwtKeyRing ring;
  private JwtKey key;
  private AccessTokenService service;
  private final UUID user = UUID.randomUUID();
  private final UUID sid = UUID.randomUUID();

  private AccessTokenService at(Instant now) {
    return new AccessTokenService(
        ring, Clock.fixed(now, ZoneOffset.UTC), AuthTestProperties.defaults());
  }

  @BeforeEach
  void keys() {
    key = JwtKey.generate();
    ring = new JwtKeyRing();
    ring.install(key, null);
    service = at(T0);
  }

  private Map<String, Object> header() {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("alg", "EdDSA");
    h.put("typ", "JWT");
    h.put("kid", key.kid());
    return h;
  }

  private Map<String, Object> claims() {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("sub", user.toString());
    c.put("sid", sid.toString());
    c.put("role", "viewer");
    c.put("perm", List.of());
    c.put("iat", T0.getEpochSecond());
    c.put("exp", T0.getEpochSecond() + 900);
    c.put("jti", UUID.randomUUID().toString());
    return c;
  }

  private static String segment(Object value) {
    return B64.encodeToString(JSON.writeValueAsBytes(value));
  }

  private static String signed(JwtKey signer, Map<String, Object> h, Map<String, Object> c) {
    String input = segment(h) + "." + segment(c);
    return input + "." + B64.encodeToString(signer.sign(input.getBytes(StandardCharsets.US_ASCII)));
  }

  private TokenCheck.Rejection rejection(String token) {
    TokenCheck check = service.verify(token);
    assertThat(check.valid()).as("a forged token must not verify").isFalse();
    return check.rejection();
  }

  @Test
  @DisplayName("T-SEC-41a a token has the 16 §7.1 header and claims, exp = iat + 15 minutes")
  void issuedShape() {
    String token = service.issue(user, sid, Role.ADMIN, Set.of("pii.view"));
    String[] parts = token.split("\\.", -1);
    assertThat(parts).hasSize(3);
    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
    assertThat(header.path("alg").asString()).isEqualTo("EdDSA");
    assertThat(header.path("typ").asString()).isEqualTo("JWT");
    assertThat(header.path("kid").asString()).isEqualTo(key.kid());
    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertThat(claims.path("sub").asString()).isEqualTo(user.toString());
    assertThat(claims.path("sid").asString()).isEqualTo(sid.toString());
    assertThat(claims.path("role").asString()).isEqualTo("admin");
    assertThat(claims.path("perm").get(0).asString()).isEqualTo("pii.view");
    assertThat(claims.path("iat").asLong()).isEqualTo(T0.getEpochSecond());
    assertThat(claims.path("exp").asLong()).isEqualTo(T0.getEpochSecond() + 900);
    assertThat(claims.path("jti").asString()).isNotEmpty();
    assertThat(Base64.getUrlDecoder().decode(parts[2])).hasSize(64);

    TokenCheck check = service.verify(token);
    assertThat(check.valid()).isTrue();
    assertThat(check.claims().subject()).isEqualTo(user);
    assertThat(check.claims().sid()).isEqualTo(sid);
    assertThat(check.claims().role()).isEqualTo(Role.ADMIN);
    assertThat(check.claims().permissions()).containsExactly("pii.view");
    assertThat(token).hasSizeLessThan(1024);
  }

  @Test
  @DisplayName("T-SEC-41a expiry follows the injected clock: valid until exp, refused from exp on")
  void expiry() {
    String token = service.issue(user, sid, Role.VIEWER, Set.of());
    assertThat(at(T0.plusSeconds(899)).verify(token).valid()).isTrue();
    assertThat(at(T0.plusSeconds(900)).verify(token).rejection())
        .isEqualTo(TokenCheck.Rejection.EXPIRED);
    assertThat(at(T0.plus(Duration.ofDays(1))).verify(token).rejection())
        .isEqualTo(TokenCheck.Rejection.EXPIRED);
  }

  @Test
  @DisplayName("T-SEC-41a alg none is refused, signed or not")
  void algNone() {
    Map<String, Object> h = header();
    h.put("alg", "none");
    String input = segment(h) + "." + segment(claims());
    assertThat(rejection(input + "." + B64.encodeToString(new byte[64])))
        .isEqualTo(TokenCheck.Rejection.UNSUPPORTED_ALGORITHM);
    assertThat(rejection(input + ".")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection(input)).isEqualTo(TokenCheck.Rejection.MALFORMED);
    h.put("alg", "None");
    assertThat(
            rejection(
                segment(h) + "." + segment(claims()) + "." + B64.encodeToString(new byte[64])))
        .isEqualTo(TokenCheck.Rejection.UNSUPPORTED_ALGORITHM);
  }

  @Test
  @DisplayName("T-SEC-41a HS256 keyed with the public key (algorithm confusion) is refused")
  void hs256() throws Exception {
    Map<String, Object> h = header();
    h.put("alg", "HS256");
    String input = segment(h) + "." + segment(claims());
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key.kid().getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
    String sig = B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    assertThat(rejection(input + "." + sig)).isEqualTo(TokenCheck.Rejection.UNSUPPORTED_ALGORITHM);
  }

  @Test
  @DisplayName("T-SEC-41a a header with unknown members (crit, jwk, x5u) is refused")
  void headerMembers() {
    for (String member : List.of("crit", "jwk", "jku", "x5u", "x5c", "b64")) {
      Map<String, Object> h = header();
      h.put(member, "x");
      assertThat(rejection(signed(key, h, claims())))
          .as(member)
          .isEqualTo(TokenCheck.Rejection.UNSUPPORTED_ALGORITHM);
    }
    Map<String, Object> wrongType = header();
    wrongType.put("typ", "at+jwt");
    assertThat(rejection(signed(key, wrongType, claims())))
        .isEqualTo(TokenCheck.Rejection.UNSUPPORTED_ALGORITHM);
  }

  @Test
  @DisplayName("T-SEC-41a an unknown or missing kid is refused")
  void unknownKid() {
    Map<String, Object> h = header();
    h.put("kid", "AAAAAAAAAAAAAAAA");
    assertThat(rejection(signed(key, h, claims()))).isEqualTo(TokenCheck.Rejection.UNKNOWN_KEY);
    h.remove("kid");
    assertThat(rejection(signed(key, h, claims()))).isEqualTo(TokenCheck.Rejection.UNKNOWN_KEY);
    h.put("kid", 7);
    assertThat(rejection(signed(key, h, claims()))).isEqualTo(TokenCheck.Rejection.UNKNOWN_KEY);
  }

  @Test
  @DisplayName("T-SEC-41a a changed payload, header or signature, or another key, is refused")
  void tampering() {
    String token = service.issue(user, sid, Role.VIEWER, Set.of());
    String[] parts = token.split("\\.", -1);
    Map<String, Object> adminClaims = claims();
    adminClaims.put("role", "admin");
    assertThat(rejection(parts[0] + "." + segment(adminClaims) + "." + parts[2]))
        .isEqualTo(TokenCheck.Rejection.BAD_SIGNATURE);

    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[10] ^= 1;
    assertThat(rejection(parts[0] + "." + parts[1] + "." + B64.encodeToString(signature)))
        .isEqualTo(TokenCheck.Rejection.BAD_SIGNATURE);
    assertThat(rejection(parts[0] + "." + parts[1] + "." + B64.encodeToString(new byte[10])))
        .isEqualTo(TokenCheck.Rejection.BAD_SIGNATURE);

    JwtKey other = JwtKey.generate();
    Map<String, Object> h = header();
    assertThat(rejection(signed(other, h, claims()))).isEqualTo(TokenCheck.Rejection.BAD_SIGNATURE);
  }

  @Test
  @DisplayName("T-SEC-41a malformed segments and tokens over 4 KB are refused without parsing")
  void malformed() {
    assertThat(rejection(null)).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("abc")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("a.b")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("..")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("a.b.c.d")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("a!.b.c")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    assertThat(rejection("abcd.abcd.abcd")).isEqualTo(TokenCheck.Rejection.MALFORMED);
    String notAnObject = B64.encodeToString("[1,2]".getBytes(StandardCharsets.US_ASCII));
    assertThat(rejection(notAnObject + "." + notAnObject + ".AAAA"))
        .isEqualTo(TokenCheck.Rejection.MALFORMED);
    String padded = service.issue(user, sid, Role.VIEWER, Set.of()) + "=";
    assertThat(rejection(padded)).isEqualTo(TokenCheck.Rejection.MALFORMED);

    Map<String, Object> big = claims();
    big.put("pad", "x".repeat(AccessTokenService.MAX_TOKEN_LENGTH));
    assertThat(rejection(signed(key, header(), big))).isEqualTo(TokenCheck.Rejection.TOO_LARGE);
    assertThat(rejection("A".repeat(AccessTokenService.MAX_TOKEN_LENGTH + 1)))
        .isEqualTo(TokenCheck.Rejection.TOO_LARGE);
  }

  @Test
  @DisplayName("T-SEC-41a duplicate JSON members are refused (no parser differential)")
  void duplicateMembers() {
    String h =
        B64.encodeToString(
            ("{\"alg\":\"EdDSA\",\"alg\":\"none\",\"kid\":\"" + key.kid() + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    String input = h + "." + segment(claims());
    assertThat(rejection(input + "." + B64.encodeToString(new byte[64])))
        .isEqualTo(TokenCheck.Rejection.MALFORMED);
  }

  @Test
  @DisplayName("T-SEC-41a iat and nbf in the future beyond 60 s, and missing claims, are refused")
  void timesAndClaims() {
    Map<String, Object> future = claims();
    future.put("iat", T0.getEpochSecond() + 61);
    future.put("exp", T0.getEpochSecond() + 900);
    assertThat(rejection(signed(key, header(), future)))
        .isEqualTo(TokenCheck.Rejection.NOT_YET_VALID);

    Map<String, Object> slightly = claims();
    slightly.put("iat", T0.getEpochSecond() + 60);
    slightly.put("exp", T0.getEpochSecond() + 900);
    assertThat(service.verify(signed(key, header(), slightly)).valid()).isTrue();

    Map<String, Object> notBefore = claims();
    notBefore.put("nbf", T0.getEpochSecond() + 61);
    assertThat(rejection(signed(key, header(), notBefore)))
        .isEqualTo(TokenCheck.Rejection.NOT_YET_VALID);

    Map<String, Object> tooLong = claims();
    tooLong.put("exp", T0.getEpochSecond() + 900 + 61);
    assertThat(rejection(signed(key, header(), tooLong)))
        .isEqualTo(TokenCheck.Rejection.INVALID_CLAIMS);

    Map<String, Object> backwards = claims();
    backwards.put("exp", T0.getEpochSecond());
    assertThat(rejection(signed(key, header(), backwards)))
        .isEqualTo(TokenCheck.Rejection.INVALID_CLAIMS);

    for (String claim : List.of("sub", "sid", "role", "perm", "iat", "exp", "jti")) {
      Map<String, Object> missing = claims();
      missing.remove(claim);
      assertThat(rejection(signed(key, header(), missing)))
          .as(claim)
          .isEqualTo(TokenCheck.Rejection.INVALID_CLAIMS);
    }

    Map<String, Object> publicRole = claims();
    publicRole.put("role", "public");
    assertThat(rejection(signed(key, header(), publicRole)))
        .isEqualTo(TokenCheck.Rejection.INVALID_CLAIMS);
    Map<String, Object> shortId = claims();
    shortId.put("sid", "1-1-1-1-1");
    assertThat(rejection(signed(key, header(), shortId)))
        .isEqualTo(TokenCheck.Rejection.INVALID_CLAIMS);
  }

  @Test
  @DisplayName(
      "T-SEC-41a rotation: tokens of the previous key still verify (kid), the new key signs,"
          + " a public key can only verify")
  void rotation() {
    String old = service.issue(user, sid, Role.VIEWER, Set.of());
    JwtKey next = JwtKey.generate();
    ring.install(next, key);
    assertThat(service.verify(old).valid()).isTrue();
    String fresh = service.issue(user, sid, Role.VIEWER, Set.of());
    assertThat(
            JSON.readTree(Base64.getUrlDecoder().decode(fresh.split("\\.", -1)[0]))
                .path("kid")
                .asString())
        .isEqualTo(next.kid());
    assertThat(service.verify(fresh).valid()).isTrue();

    ring.install(next, null);
    assertThat(service.verify(old).rejection()).isEqualTo(TokenCheck.Rejection.UNKNOWN_KEY);
  }
}
