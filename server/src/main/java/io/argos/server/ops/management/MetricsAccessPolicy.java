/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.CidrRange;
import io.argos.server.config.ConfigValidation;
import io.argos.server.config.SecretValues;
import io.argos.server.config.SecretValues.SecretException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Who may read {@code /actuator/info} and {@code /actuator/prometheus} on the management port
 * (SEC-55, 02 C-7, 16 §7.12): a client whose address lies in {@code argos.metrics.allow-cidrs}
 * (empty: nobody) and that presents {@code Authorization: Bearer <token>} with the token of {@code
 * argos.metrics.token-file} or {@code ARGOS_METRICS_TOKEN}. The token is compared in constant time
 * (SHA-256 digests, {@link MessageDigest#isEqual}); only its digest is kept.
 *
 * <p>Start step {@code metrics-access} ({@code CONFIG}): loads the token and the ranges, refuses
 * the start when the token file cannot be read, and warns when nobody can read the endpoints (no
 * token or no range) or when the token is shorter than {@value #RECOMMENDED_TOKEN_LENGTH}
 * characters.
 */
@Component
@Order(ConfigValidation.ORDER + 150)
public class MetricsAccessPolicy implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(MetricsAccessPolicy.class);

  /** Length of a generated token (32 random bytes, base64url: 43 characters). */
  static final int RECOMMENDED_TOKEN_LENGTH = 32;

  /** The decision for one request. */
  public enum Decision {
    /** Address allowed and token correct. */
    ALLOW,
    /** Address allowed, token missing or wrong: 401. */
    UNAUTHENTICATED,
    /** Address outside {@code allow-cidrs}: 403. */
    FORBIDDEN
  }

  private final ArgosProperties.Metrics settings;
  private volatile List<CidrRange> ranges = List.of();
  private volatile byte[] tokenDigest;

  public MetricsAccessPolicy(ArgosProperties properties) {
    this.settings = properties.metrics();
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "metrics-access";
  }

  @Override
  public void run() throws StartupPhaseException {
    Optional<String> token;
    try {
      token =
          SecretValues.resolve(
              "argos.metrics.token-file",
              settings.tokenFile(),
              "argos.metrics.token",
              settings.token());
    } catch (SecretException e) {
      throw new StartupPhaseException(e.getMessage());
    }
    // Invalid ranges were reported by config-validation, which runs first.
    ranges = settings.allowCidrs().stream().map(CidrRange::parse).toList();
    tokenDigest = token.filter(t -> !t.isBlank()).map(MetricsAccessPolicy::sha256).orElse(null);
    if (tokenDigest == null) {
      LOG.warn(
          "No metrics token (argos.metrics.token-file or ARGOS_METRICS_TOKEN): /actuator/info and"
              + " /actuator/prometheus refuse every request (SEC-55)");
    } else if (token.get().length() < RECOMMENDED_TOKEN_LENGTH) {
      LOG.warn(
          "The metrics token is shorter than {} characters; generate 32 random bytes (16 §10.6)",
          RECOMMENDED_TOKEN_LENGTH);
    }
    if (ranges.isEmpty()) {
      LOG.warn(
          "argos.metrics.allow-cidrs is empty: /actuator/info and /actuator/prometheus refuse"
              + " every client (16 §7.12)");
    }
  }

  /**
   * The decision for a client address and an {@code Authorization} header value. The address is
   * checked first, so a client outside the ranges learns nothing about the token.
   */
  public Decision decide(String remoteAddress, String authorization) {
    Optional<InetAddress> address = CidrRange.literal(remoteAddress);
    List<CidrRange> allowed = ranges;
    if (address.isEmpty() || allowed.stream().noneMatch(r -> r.contains(address.get()))) {
      return Decision.FORBIDDEN;
    }
    byte[] expected = tokenDigest;
    String presented = bearer(authorization);
    if (expected == null || presented == null) {
      return Decision.UNAUTHENTICATED;
    }
    return MessageDigest.isEqual(expected, sha256(presented))
        ? Decision.ALLOW
        : Decision.UNAUTHENTICATED;
  }

  /** The credential of {@code Bearer <credential>} (scheme in any case), or null. */
  static String bearer(String authorization) {
    if (authorization == null) {
      return null;
    }
    String value = authorization.strip();
    if (value.length() < 7 || !value.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
      return null;
    }
    String credential = value.substring(7).strip();
    return credential.isEmpty() ? null : credential;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from the JDK", e);
    }
  }
}
