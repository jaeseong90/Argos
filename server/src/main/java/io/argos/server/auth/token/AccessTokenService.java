/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import io.argos.server.auth.Role;
import io.argos.server.config.ArgosProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues and verifies the access tokens of the UI sessions (16 §7.1, 12 §7.2, DSN-12-10): compact
 * JWS, {@code EdDSA}/Ed25519 (RFC 8037) with the JDK's own implementation.
 *
 * <p>Header {@code {"alg":"EdDSA","typ":"JWT","kid":...}}; claims {@code sub} (user id), {@code
 * sid} (session row), {@code role}, {@code perm}, {@code iat}, {@code exp} = {@code iat} + {@code
 * argos.auth.access-ttl}, {@code jti}.
 *
 * <p>The parser is strict and checks in this order, rejecting at the first failure (no exception
 * text reaches a client): length at most {@value #MAX_TOKEN_LENGTH} characters; exactly three
 * non-empty base64url segments without padding; a JSON object header (duplicate names rejected)
 * with {@code alg} exactly {@code EdDSA} ({@code none}, {@code HS256}, ... rejected), {@code typ}
 * absent or {@code JWT}, no {@code crit}, and a {@code kid} of the current or the previous key; the
 * Ed25519 signature; a JSON object payload with every claim present and well typed; {@code iat} and
 * {@code nbf} not later than now + {@value #CLOCK_SKEW_SECONDS} s, {@code exp} after {@code iat}
 * and at most the access lifetime + tolerance after it, and now before {@code exp}. "Now" is the
 * injected {@link Clock}. A token is never logged.
 */
@Service
public class AccessTokenService {

  /** Upper bound of a token (16 §7.1; a valid token is about 400 characters). */
  public static final int MAX_TOKEN_LENGTH = 4096;

  /** Tolerance for {@code iat} and {@code nbf} in the future (seconds). */
  public static final long CLOCK_SKEW_SECONDS = 60;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final TypeReference<LinkedHashMap<String, Object>> OBJECT =
      new TypeReference<>() {};
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Set<String> HEADER_NAMES = Set.of("alg", "typ", "kid");

  private final JwtKeyRing keys;
  private final Clock clock;
  private final Duration accessTtl;

  public AccessTokenService(JwtKeyRing keys, Clock clock, ArgosProperties properties) {
    this.keys = keys;
    this.clock = clock;
    this.accessTtl = properties.auth().accessTtl();
  }

  /** The access-token lifetime ({@code argos.auth.access-ttl}). */
  public Duration accessTtl() {
    return accessTtl;
  }

  /**
   * Issues a token for a session.
   *
   * @param userId {@code sub}
   * @param sid {@code sid}
   * @param role {@code role} (hint)
   * @param permissions {@code perm} (hint)
   */
  public String issue(UUID userId, UUID sid, Role role, Set<String> permissions) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(sid, "sid");
    Objects.requireNonNull(role, "role");
    JwtKey key = keys.signingKey();
    long iat = clock.instant().getEpochSecond();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", JwtKey.ALGORITHM);
    header.put("typ", "JWT");
    header.put("kid", key.kid());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", userId.toString());
    claims.put("sid", sid.toString());
    claims.put("role", role.extensionValue());
    claims.put("perm", permissions == null ? List.of() : permissions.stream().sorted().toList());
    claims.put("iat", iat);
    claims.put("exp", iat + accessTtl.toSeconds());
    claims.put("jti", UUID.randomUUID().toString());
    String signingInput =
        ENCODER.encodeToString(MAPPER.writeValueAsBytes(header))
            + "."
            + ENCODER.encodeToString(MAPPER.writeValueAsBytes(claims));
    byte[] signature = key.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + ENCODER.encodeToString(signature);
  }

  /** Verifies a token (see the class comment for the checks). */
  public TokenCheck verify(String token) {
    if (token == null || token.isEmpty()) {
      return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
    }
    if (token.length() > MAX_TOKEN_LENGTH) {
      return TokenCheck.rejected(TokenCheck.Rejection.TOO_LARGE);
    }
    int first = token.indexOf('.');
    int second = first < 0 ? -1 : token.indexOf('.', first + 1);
    if (first <= 0 || second <= first + 1 || second == token.length() - 1) {
      return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
    }
    if (token.indexOf('.', second + 1) >= 0) {
      return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c != '.' && !SecureTokens.isBase64Url(c)) {
        return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
      }
    }
    Optional<Map<String, Object>> header = json(token.substring(0, first));
    if (header.isEmpty()) {
      return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
    }
    Map<String, Object> h = header.get();
    if (!JwtKey.ALGORITHM.equals(h.get("alg"))
        || (h.containsKey("typ") && !"JWT".equals(h.get("typ")))
        || !HEADER_NAMES.containsAll(h.keySet())) {
      return TokenCheck.rejected(TokenCheck.Rejection.UNSUPPORTED_ALGORITHM);
    }
    Optional<JwtKey> key =
        h.get("kid") instanceof String kid ? keys.verificationKey(kid) : Optional.empty();
    if (key.isEmpty()) {
      return TokenCheck.rejected(TokenCheck.Rejection.UNKNOWN_KEY);
    }
    byte[] signature;
    try {
      signature = DECODER.decode(token.substring(second + 1));
    } catch (IllegalArgumentException e) {
      return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
    }
    byte[] signingInput = token.substring(0, second).getBytes(StandardCharsets.US_ASCII);
    if (signature.length != 64 || !key.get().verify(signingInput, signature)) {
      return TokenCheck.rejected(TokenCheck.Rejection.BAD_SIGNATURE);
    }
    Optional<Map<String, Object>> payload = json(token.substring(first + 1, second));
    if (payload.isEmpty()) {
      return TokenCheck.rejected(TokenCheck.Rejection.MALFORMED);
    }
    return claims(payload.get());
  }

  private TokenCheck claims(Map<String, Object> p) {
    Optional<UUID> sub = uuid(p.get("sub"));
    Optional<UUID> sid = uuid(p.get("sid"));
    Optional<Long> iat = seconds(p.get("iat"));
    Optional<Long> exp = seconds(p.get("exp"));
    Optional<Set<String>> perm = strings(p.get("perm"));
    Object jti = p.get("jti");
    Role role = null;
    if (p.get("role") instanceof String r) {
      try {
        role = Role.ofUserRole(r);
      } catch (IllegalArgumentException e) {
        role = null;
      }
    }
    Optional<Long> nbf = p.containsKey("nbf") ? seconds(p.get("nbf")) : Optional.of(0L);
    if (sub.isEmpty()
        || sid.isEmpty()
        || iat.isEmpty()
        || exp.isEmpty()
        || perm.isEmpty()
        || nbf.isEmpty()
        || role == null
        || !(jti instanceof String tokenId)
        || tokenId.isEmpty()
        || tokenId.length() > 64) {
      return TokenCheck.rejected(TokenCheck.Rejection.INVALID_CLAIMS);
    }
    long issued = iat.get();
    long expires = exp.get();
    if (expires <= issued || expires - issued > accessTtl.toSeconds() + CLOCK_SKEW_SECONDS) {
      return TokenCheck.rejected(TokenCheck.Rejection.INVALID_CLAIMS);
    }
    long now = clock.instant().getEpochSecond();
    if (issued > now + CLOCK_SKEW_SECONDS || nbf.get() > now + CLOCK_SKEW_SECONDS) {
      return TokenCheck.rejected(TokenCheck.Rejection.NOT_YET_VALID);
    }
    if (now >= expires) {
      return TokenCheck.rejected(TokenCheck.Rejection.EXPIRED);
    }
    return TokenCheck.valid(
        new AccessTokenClaims(sub.get(), sid.get(), role, perm.get(), issued, expires, tokenId));
  }

  private static Optional<Map<String, Object>> json(String segment) {
    try {
      byte[] bytes = DECODER.decode(segment);
      return Optional.of(MAPPER.readValue(bytes, OBJECT));
    } catch (IllegalArgumentException | JacksonException e) {
      return Optional.empty();
    }
  }

  private static Optional<UUID> uuid(Object value) {
    if (!(value instanceof String text) || text.length() != 36) {
      return Optional.empty();
    }
    try {
      UUID parsed = UUID.fromString(text);
      return parsed.toString().equals(text) ? Optional.of(parsed) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<Long> seconds(Object value) {
    if (value instanceof Integer i && i >= 0) {
      return Optional.of(i.longValue());
    }
    if (value instanceof Long l && l >= 0) {
      return Optional.of(l);
    }
    return Optional.empty();
  }

  private static Optional<Set<String>> strings(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 16) {
      return Optional.empty();
    }
    Set<String> out = new LinkedHashSet<>();
    List<Object> items = new ArrayList<>(list);
    for (Object item : items) {
      if (!(item instanceof String s) || s.isEmpty() || s.length() > 64) {
        return Optional.empty();
      }
      out.add(s);
    }
    return Optional.of(out);
  }
}
