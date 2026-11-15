/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The keyset cursor (14 §2.3, DSN-14-03): opaque, bounded, tamper-evident. */
@Tag("req:DSN-14-03")
class CursorCodecTest {

  private static final byte[] KEY = new byte[32];

  static {
    Arrays.fill(KEY, (byte) 7);
  }

  private final CursorCodec codec = new CursorCodec(KEY);

  private static void invalid(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.FILTER_INVALID);
              assertThat(e.details()).containsExactly(new ErrorBody.Detail("cursor", "invalid"));
            });
  }

  @Test
  @DisplayName("T-API-004a a cursor round-trips its keyset values within its scope")
  void roundTrip() {
    List<String> values = List.of("1761180600123", "7312000000000001", "naïve ☃");
    String cursor = codec.encode("transactions_search|abc", values);
    assertThat(cursor).matches("[A-Za-z0-9_-]+").hasSizeLessThanOrEqualTo(512);
    assertThat(codec.decode("transactions_search|abc", cursor)).isEqualTo(values);
    assertThat(codec.decode("s", codec.encode("s", List.of()))).isEmpty();
  }

  @Test
  @DisplayName(
      "T-API-004a a tampered, foreign-scope, other-key, oversized or garbage cursor is 400")
  void tampering() {
    String cursor = codec.encode("scope", List.of("100", "200"));
    byte[] raw = Base64.getUrlDecoder().decode(cursor);
    for (int i = 0; i < raw.length; i++) {
      byte[] changed = raw.clone();
      changed[i] ^= 1;
      String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(changed);
      invalid(() -> codec.decode("scope", forged));
    }
    invalid(() -> codec.decode("other-scope", cursor));
    byte[] otherKey = KEY.clone();
    otherKey[0] = 8;
    invalid(() -> new CursorCodec(otherKey).decode("scope", cursor));
    invalid(() -> codec.decode("scope", "x".repeat(513)));
    invalid(() -> codec.decode("scope", "not base64 !"));
    invalid(() -> codec.decode("scope", ""));
    invalid(() -> codec.decode("scope", null));
    invalid(() -> codec.decode("scope", cursor.substring(0, cursor.length() - 2)));
  }

  @Test
  @DisplayName(
      "T-API-004a a cursor whose text differs only in the unused bits of its last character or in"
          + " padding is 400 (the exact issued text only)")
  void nonCanonicalText() {
    int checked = 0;
    for (int n = 0; n < 12; n++) {
      String cursor = codec.encode("scope", List.of("x".repeat(n)));
      int unusedBits = (cursor.length() % 4) == 2 ? 4 : (cursor.length() % 4) == 3 ? 2 : 0;
      if (unusedBits == 0) {
        continue;
      }
      String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
      int last = alphabet.indexOf(cursor.charAt(cursor.length() - 1));
      String forged = cursor.substring(0, cursor.length() - 1) + alphabet.charAt(last ^ 1);
      assertThat(Base64.getUrlDecoder().decode(forged))
          .as("the decoder maps both texts to the same bytes")
          .isEqualTo(Base64.getUrlDecoder().decode(cursor));
      invalid(() -> codec.decode("scope", forged));
      String padded = cursor + (unusedBits == 4 ? "==" : "=");
      invalid(() -> codec.decode("scope", padded));
      checked++;
    }
    assertThat(checked).isGreaterThan(0);
  }

  @Test
  @DisplayName("two servers (two random keys) do not accept each other's cursors")
  void randomKeys() {
    String cursor = new CursorCodec().encode("s", List.of("1"));
    invalid(() -> new CursorCodec().decode("s", cursor));
  }

  @Test
  @DisplayName("an endpoint cannot produce a cursor beyond the size bound")
  void sizeBound() {
    assertThatThrownBy(() -> codec.encode("s", List.of("x".repeat(400))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> codec.encode("s", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
