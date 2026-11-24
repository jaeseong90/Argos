/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.privacy.salt.AppSaltServiceAccess;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server's hashed user id equals the agent's (16 §6.3, ADR-0010 C-163 (1)): base64url without
 * padding of HMAC-SHA256(salt, UTF-8 id), first 16 characters. The reference here encodes with the
 * agent's own algorithm (6-bit groups by hand, as {@code UserIds.base64Url} does), not with {@code
 * java.util.Base64}.
 */
@Tag("req:SEC-32")
class AppSaltHashTest {

  private static final byte[] SALT =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private static final char[] ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

  private static String agentHash(byte[] salt, String id) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(salt, "HmacSHA256"));
    byte[] d = mac.doFinal(id.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (int i = 0; out.length() < 16; i += 3) {
      int b0 = d[i] & 0xff;
      int b1 = d[i + 1] & 0xff;
      int b2 = d[i + 2] & 0xff;
      out.append(ALPHABET[b0 >>> 2]);
      out.append(ALPHABET[((b0 & 0x3) << 4) | (b1 >>> 4)]);
      out.append(ALPHABET[((b1 & 0xf) << 2) | (b2 >>> 6)]);
      out.append(ALPHABET[b2 & 0x3f]);
    }
    return out.substring(0, 16);
  }

  @Test
  @DisplayName("T-SEC-32b the server's hashed user id is the agent's, 16 base64url characters")
  void sameAsAgent() throws Exception {
    for (String id : new String[] {"user-1", "alice@example.com", "사용자", "x"}) {
      String server = AppSaltServiceAccess.hash(SALT, id);
      assertThat(server).hasSize(16).matches("[A-Za-z0-9_-]{16}");
      assertThat(server).as(id).isEqualTo(agentHash(SALT, id));
    }
    byte[] other = SALT.clone();
    other[0] = 1;
    assertThat(AppSaltServiceAccess.hash(other, "user-1"))
        .isNotEqualTo(AppSaltServiceAccess.hash(SALT, "user-1"));
  }
}
