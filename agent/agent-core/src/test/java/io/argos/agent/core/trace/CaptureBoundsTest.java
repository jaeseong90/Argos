/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bounds and contracts of the request data rules that the WO-12 review (r1) asked to pin: the
 * {@code X-Forwarded-For} hop bound of {@link RemoteIps#client} (SEC-37), the parameter bound of
 * {@code capture.query-string: whitelist} (SEC-34), the normalization before the user id HMAC
 * (SEC-32, 16 §6.3), and {@code capture.user-id} on {@code Tracer.setHttp} (SEC-32, C-125).
 */
class CaptureBoundsTest {
  private static final byte[] SALT =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  // --- X-Forwarded-For hop bound (SEC-37) -----------------------------------------------------

  @Test
  @Tag("req:SEC-37")
  @DisplayName(
      "T-AG-017a/T-SEC-37a XFF hop bound: 32 hops from the right; all trusted gives the 32nd from"
          + " the right; an untrusted 33rd hop is never reached, an untrusted 32nd is the client")
  void forwardedHopBound() {
    RemoteIps.Trusted trusted = RemoteIps.Trusted.of(new String[] {"10.0.0.0/8"});
    assertThat(RemoteIps.MAX_FORWARDED_HOPS).isEqualTo(32);

    // 40 trusted hops 10.0.0.1 .. 10.0.0.40: the 32 rightmost are read (40 .. 9).
    List<String> hops = new ArrayList<String>();
    for (int i = 1; i <= 40; i++) {
      hops.add("10.0.0." + i);
    }
    assertThat(RemoteIps.client("10.0.0.250", String.join(", ", hops), trusted))
        .as("all trusted: the leftmost hop read")
        .isEqualTo("10.0.0.9");

    // An untrusted 33rd hop from the right (left of 32 trusted ones) is not read.
    List<String> beyond = new ArrayList<String>();
    beyond.add("198.51.100.1");
    for (int i = 1; i <= RemoteIps.MAX_FORWARDED_HOPS; i++) {
      beyond.add("10.0.0." + i);
    }
    assertThat(RemoteIps.client("10.0.0.250", String.join(",", beyond), trusted))
        .as("the 33rd hop is beyond the bound: the 32nd from the right (a trusted proxy)")
        .isEqualTo("10.0.0.1");

    // The 32nd hop from the right is still read: an untrusted one there is the client.
    List<String> within = new ArrayList<String>();
    within.add("198.51.100.1");
    for (int i = 1; i < RemoteIps.MAX_FORWARDED_HOPS; i++) {
      within.add("10.0.0." + i);
    }
    assertThat(RemoteIps.client("10.0.0.250", String.join(",", within), trusted))
        .isEqualTo("198.51.100.1");
  }

  // --- query string parameter bound (SEC-34) --------------------------------------------------

  @Test
  @Tag("req:SEC-34")
  @DisplayName(
      "T-AG-014a/T-SEC-34a whitelist reads at most 256 parameters: the 256th is kept, the 257th"
          + " and later are never looked at")
  void whitelistParameterBound() {
    assertThat(HttpPaths.MAX_QUERY_PARAMETERS).isEqualTo(256);
    StringBuilder query = new StringBuilder();
    for (int i = 1; i <= 300; i++) {
      if (query.length() > 0) {
        query.append('&');
      }
      query.append('p').append(i).append('=').append(i);
    }
    String[] names = {"p1", "p256", "p257", "p300"};
    assertThat(HttpPaths.whitelisted(query.toString(), names)).isEqualTo("p1=1&p256=256");
    assertThat(
            HttpPaths.recorded(
                "/a",
                query.toString(),
                CaptureConfig.QUERY_STRING_WHITELIST,
                new String[] {"p257"}))
        .isEqualTo("/a");
    // Empty items (&&) are not parameters and do not count.
    assertThat(HttpPaths.whitelisted("&&&" + query, new String[] {"p256"})).isEqualTo("p256=256");
  }

  // --- user id hashing input (SEC-32, 16 §6.3) ------------------------------------------------

  @Test
  @Tag("req:SEC-32")
  @DisplayName(
      "T-SEC-32a hashed hashes the normalized id (control characters removed, 128 chars, trimmed):"
          + " ' kim'+BEL hashes as base64url(HMAC(salt, 'kim'))[0:16]")
  void hashedHashesTheNormalizedId() throws Exception {
    UserIds ids = new UserIds(new AgentCounters(), salt(SALT));
    CaptureConfig hashed =
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_HASHED)
            .userIdSource("header:x-user")
            .build();
    Transaction tx = transaction();
    ids.apply(tx, hashed, " kim\u0007");
    assertThat(tx.userId()).isEqualTo(expectedHash("kim")).hasSize(UserIds.HASH_CHARS);
    assertThat(tx.userId()).matches("[A-Za-z0-9_-]{16}");

    StringBuilder longId = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longId.append((char) ('a' + i % 26));
    }
    Transaction cut = transaction();
    ids.apply(cut, hashed, longId.toString());
    assertThat(cut.userId())
        .as("cut at 128 characters before the HMAC")
        .isEqualTo(expectedHash(longId.substring(0, CaptureConfig.USER_ID_MAX_LENGTH)));
  }

  // --- Tracer.setHttp user id (SEC-32, C-125) -------------------------------------------------

  @Test
  @Tag("req:SEC-32")
  @DisplayName(
      "T-SEC-32a Tracer.setHttp applies capture.user-id: off -> none; hashed without salt -> none"
          + " and no_salt counted; hashed with salt -> 16-char hash; plain -> normalized value")
  void setHttpAppliesTheUserIdMode() throws Exception {
    final List<Transaction> ended = new ArrayList<Transaction>();
    AgentCounters counters = new AgentCounters();
    final AtomicReference<byte[]> salt = new AtomicReference<byte[]>(new byte[0]);
    TracerImpl tracer =
        TracerImpl.builder()
            .counters(counters)
            .userIdSalts(
                new UserIds.SaltSource() {
                  @Override
                  public byte[] salt() {
                    return salt.get();
                  }
                })
            .sink(
                new TracerImpl.Sink() {
                  @Override
                  public void accept(Transaction tx) {
                    ended.add(tx);
                  }
                })
            .build();

    // off (the default)
    assertThat(setHttp(tracer, ended, " kim\u0007").userId()).isNull();

    CaptureConfig hashed =
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_HASHED)
            .userIdSource("header:x-user")
            .build();
    tracer.setCapture(hashed);
    assertThat(setHttp(tracer, ended, "kim").userId()).as("never plain without a salt").isNull();
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT)).isEqualTo(1);

    salt.set(SALT);
    assertThat(setHttp(tracer, ended, " kim\u0007").userId())
        .isEqualTo(expectedHash("kim"))
        .hasSize(UserIds.HASH_CHARS);
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT)).isEqualTo(1);

    tracer.setCapture(
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("header:x-user")
            .build());
    assertThat(setHttp(tracer, ended, " ki\u0000m\n").userId()).isEqualTo("kim");
    Transaction none = setHttp(tracer, ended, null);
    assertThat(none.userId()).isNull();
    assertThat(none.path()).isEqualTo("/a");
  }

  /** One transaction with {@code setHttp(.., userId)}; returns its record. */
  private static Transaction setHttp(TracerImpl tracer, List<Transaction> ended, String userId) {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /a", System.nanoTime());
    tracer.setHttp(ctx, "GET", "/a", "192.0.2.1", userId);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    return ended.get(ended.size() - 1);
  }

  private static UserIds.SaltSource salt(final byte[] value) {
    return new UserIds.SaltSource() {
      @Override
      public byte[] salt() {
        return value;
      }
    };
  }

  /** base64url(HMAC-SHA256(SALT, id))[0:16], computed with the JDK alone (16 §6.3). */
  private static String expectedHash(String id) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SALT, "HmacSHA256"));
    byte[] digest = mac.doFinal(id.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
  }

  private static Transaction transaction() {
    TraceContext ctx =
        new TraceContext(1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    return new Transaction(ctx, 10, "t", "t", 0);
  }
}
