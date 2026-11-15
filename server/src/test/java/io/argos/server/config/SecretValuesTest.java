/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.newSecret;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.config.ArgosProperties.Credential;
import io.argos.server.config.SecretValues.SecretException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Secret files and values (17 §6.1, 16 §10.6, SEC-76). */
@Tag("req:SEC-76")
class SecretValuesTest {

  @TempDir Path dir;

  @Test
  @DisplayName("the first line of the file is the secret; LF and CRLF line breaks are dropped")
  void firstLine() throws Exception {
    String secret = newSecret();
    Path lf = Files.writeString(dir.resolve("lf"), secret + "\n", StandardCharsets.UTF_8);
    Path crlf =
        Files.writeString(dir.resolve("crlf"), secret + "\r\nsecond", StandardCharsets.UTF_8);
    Path none = Files.writeString(dir.resolve("none"), secret, StandardCharsets.UTF_8);
    for (Path file : new Path[] {lf, crlf, none}) {
      assertThat(SecretValues.resolve("k-file", file.toString(), "k", null)).contains(secret);
    }
  }

  @Test
  @DisplayName("the file wins over the value; the value is used without a file; neither: empty")
  void precedence() throws Exception {
    String fromFile = newSecret();
    String fromValue = newSecret();
    Path file = Files.writeString(dir.resolve("pw"), fromFile, StandardCharsets.UTF_8);
    assertThat(SecretValues.password("argos.x", new Credential("u", file.toString(), fromValue)))
        .contains(fromFile);
    assertThat(SecretValues.password("argos.x", new Credential("u", null, fromValue)))
        .contains(fromValue);
    assertThat(SecretValues.password("argos.x", new Credential("u", " ", null))).isEmpty();
  }

  @Test
  @DisplayName("an unreadable or oversized file names the key and path, never content")
  void errors() throws Exception {
    Path missing = dir.resolve("missing");
    assertThatThrownBy(
            () -> SecretValues.resolve("argos.metrics.token-file", missing.toString(), "t", null))
        .isInstanceOf(SecretException.class)
        .hasMessageStartingWith("argos.metrics.token-file names a file that cannot be read (")
        .hasMessageContaining("missing");
    String content = newSecret();
    Path big =
        Files.writeString(
            dir.resolve("big"),
            content + "x".repeat(SecretValues.MAX_FILE_BYTES),
            StandardCharsets.UTF_8);
    assertThatThrownBy(() -> SecretValues.resolve("k-file", big.toString(), "k", null))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("larger than")
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain(content));
  }
}
