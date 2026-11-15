/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The keyset cursor of paged lists ({@code cursor} / {@code meta.next_cursor}, 14 §2.3, DSN-14-03):
 * opaque to the client, bounded in size and protected against tampering.
 *
 * <ul>
 *   <li>Content: the keyset values of the last row of a page (e.g. its {@code ts} and {@code
 *       tx_id}) as strings, chosen by the endpoint.
 *   <li>Format: base64url (no padding) of {@code version(1) | count(1) | (length(2) | UTF-8)* |
 *       HMAC-SHA256[0..16)}. The MAC covers a scope string ({@code <operation>|<filter digest>})
 *       and the payload, so a cursor of another endpoint or of other filters is refused like a
 *       forged one.
 *   <li>Key: 32 random bytes generated when the server starts (no configuration, never logged or
 *       stored): cursors do not survive a restart, and the client starts the list again.
 *   <li>A cursor longer than {@value #MAX_CURSOR_CHARS} characters, malformed, of another scope or
 *       with a wrong MAC is answered 400 {@code filter_invalid} ({@code cursor/invalid}); the
 *       comparison is constant-time.
 * </ul>
 */
@Component
public class CursorCodec {

  /** Longest cursor accepted or produced. */
  public static final int MAX_CURSOR_CHARS = 512;

  /** Most values in one cursor. */
  public static final int MAX_VALUES = 8;

  private static final byte VERSION = 1;
  private static final int MAC_BYTES = 16;
  private static final String ALGORITHM = "HmacSHA256";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  /** A codec with a new random key. */
  public CursorCodec() {
    this(newKey());
  }

  /** A codec with a given key (tests). */
  CursorCodec(byte[] key) {
    if (key.length < 32) {
      throw new IllegalArgumentException("The cursor key needs 32 bytes");
    }
    this.key = new SecretKeySpec(key.clone(), ALGORITHM);
  }

  private static byte[] newKey() {
    byte[] k = new byte[32];
    RANDOM.nextBytes(k);
    return k;
  }

  /**
   * Encodes the keyset values of a page end.
   *
   * @param scope the operation and a digest of its filters, e.g. {@code transactions_search|<sha256
   *     of the filters>}
   * @param values at most {@value #MAX_VALUES} values
   * @throws IllegalArgumentException when the cursor would exceed {@value #MAX_CURSOR_CHARS}
   *     characters (a defect of the endpoint)
   */
  public String encode(String scope, List<String> values) {
    Objects.requireNonNull(scope, "scope");
    if (values.size() > MAX_VALUES) {
      throw new IllegalArgumentException("Too many cursor values");
    }
    ByteBuffer payload = ByteBuffer.allocate(MAX_CURSOR_CHARS);
    payload.put(VERSION);
    payload.put((byte) values.size());
    for (String v : values) {
      byte[] bytes = Objects.requireNonNull(v, "value").getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 0xFFFF || payload.remaining() < 2 + bytes.length + MAC_BYTES) {
        throw new IllegalArgumentException("Cursor values too long");
      }
      payload.putShort((short) bytes.length);
      payload.put(bytes);
    }
    byte[] body = Arrays.copyOf(payload.array(), payload.position());
    byte[] mac = mac(scope, body);
    byte[] all = Arrays.copyOf(body, body.length + MAC_BYTES);
    System.arraycopy(mac, 0, all, body.length, MAC_BYTES);
    String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    if (cursor.length() > MAX_CURSOR_CHARS) {
      throw new IllegalArgumentException("Cursor longer than " + MAX_CURSOR_CHARS);
    }
    return cursor;
  }

  /**
   * Decodes a cursor of {@code scope}.
   *
   * @throws ApiException 400 {@code filter_invalid} ({@code cursor/invalid}) for anything but a
   *     cursor this server produced for {@code scope}
   */
  public List<String> decode(String scope, String cursor) {
    Objects.requireNonNull(scope, "scope");
    if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_CHARS) {
      throw invalid();
    }
    byte[] all;
    try {
      all = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
    // Only the exact text this server issued: the decoder ignores the unused low bits of the last
    // character and accepts padding, so a changed cursor could otherwise decode to the same bytes.
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(all).equals(cursor)) {
      throw invalid();
    }
    if (all.length < 2 + MAC_BYTES) {
      throw invalid();
    }
    byte[] body = Arrays.copyOf(all, all.length - MAC_BYTES);
    byte[] mac = Arrays.copyOfRange(all, all.length - MAC_BYTES, all.length);
    if (!MessageDigest.isEqual(mac, mac(scope, body))) {
      throw invalid();
    }
    ByteBuffer in = ByteBuffer.wrap(body);
    if (in.get() != VERSION) {
      throw invalid();
    }
    int count = in.get() & 0xFF;
    if (count > MAX_VALUES) {
      throw invalid();
    }
    List<String> values = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (in.remaining() < 2) {
        throw invalid();
      }
      int length = in.getShort() & 0xFFFF;
      if (in.remaining() < length) {
        throw invalid();
      }
      byte[] bytes = new byte[length];
      in.get(bytes);
      values.add(new String(bytes, StandardCharsets.UTF_8));
    }
    if (in.hasRemaining()) {
      throw invalid();
    }
    return List.copyOf(values);
  }

  private byte[] mac(String scope, byte[] body) {
    try {
      Mac m = Mac.getInstance(ALGORITHM);
      m.init(key);
      m.update(scope.getBytes(StandardCharsets.UTF_8));
      m.update((byte) 0);
      m.update(body);
      return Arrays.copyOf(m.doFinal(), MAC_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.FILTER_INVALID, "The cursor is invalid")
        .detail("cursor", "invalid");
  }
}
