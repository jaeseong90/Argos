/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The masking of the diagnostic bundle (12 §9.5, 17 §4.4, SEC-06, ADR-0010 C-94): agent tokens, the
 * setup token line, URL paths and queries, configured secret values; idempotent; the log period
 * filter.
 */
@Tag("req:SEC-06")
@Tag("req:SEC-26")
class BundleMaskingTest {

  private static final String TOKEN = "argos_" + "Ab3_-".repeat(8) + "xyz";

  @Test
  @DisplayName(
      "T-SEC-06h agent tokens become argos_****, a webhook URL keeps scheme and host only, the"
          + " setup token line loses its token, configured secrets are replaced anywhere")
  void masks() {
    assertThat(TOKEN).hasSize(49);
    String setup = "S".repeat(43);
    BundleMasking m = new BundleMasking(List.of("db-password-123456", "short"));
    String text =
        "{\"message\":\"token "
            + TOKEN
            + " at https://hooks.example.com/services/T0/B0/XYZsecret?x=1 and"
            + " syslog+tcp://siem.example.com:514 pw=db-password-123456 inline:xdb-password-123456x"
            + " Setup token (one use, valid for 30 minutes until 2026-11-24T00:30:00Z): "
            + setup
            + " short\"}";
    String masked = m.mask(text);
    assertThat(masked)
        .doesNotContain(TOKEN)
        .contains("argos_****")
        .doesNotContain("services/T0")
        .doesNotContain("XYZsecret")
        .contains("https://hooks.example.com/****")
        .contains("syslog+tcp://siem.example.com:514")
        .doesNotContain("db-password-123456")
        .doesNotContain(setup)
        .contains("): ****")
        .contains(" short\"");
    assertThat(m.mask(masked)).as("idempotent").isEqualTo(masked);
    assertThat(m.literalCount()).as("literals shorter than 8 characters are ignored").isEqualTo(1);
    assertThat(m.mask("http://127.0.0.1:8080/")).isEqualTo("http://127.0.0.1:8080/");
    assertThat(m.mask("jdbc:postgresql://pg:5432/argos?password=hunter2hunter2"))
        .doesNotContain("hunter2");
  }

  @Test
  @DisplayName(
      "T-SEC-06h the log period: lines with a ts before the start are left out, lines without one"
          + " or with an unreadable one are kept")
  void period() {
    Instant from = Instant.parse("2026-11-24T00:00:00Z");
    assertThat(DiagnosticBundle.inPeriod("{\"ts\":\"2026-11-23T23:59:59.999Z\",\"m\":1}", from))
        .isFalse();
    assertThat(DiagnosticBundle.inPeriod("{\"ts\":\"2026-11-24T00:00:00.000Z\",\"m\":1}", from))
        .isTrue();
    assertThat(DiagnosticBundle.inPeriod("plain text", from)).isTrue();
    assertThat(DiagnosticBundle.inPeriod("{\"ts\":\"yesterday-ish-value\"}", from)).isTrue();
  }

  @Test
  @DisplayName("T-SEC-06h the archive's host part keeps letters, digits, dot and dash only")
  void hostName() {
    assertThat(DiagnosticBundle.hostName()).matches("[A-Za-z0-9.-]{1,64}");
  }
}
