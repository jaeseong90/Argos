/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.storage.query.DictionaryNames.Kind;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The pure parts of the transaction API: ids, client addresses, the masked_by_role form. */
class TransactionHelpersTest {

  @Test
  @Tag("req:D-11")
  @DisplayName(
      "tx_id: unsigned decimal of 64 bits, the time prefix restores the start (2026-01-01 + id >>"
          + " 23 ms); malformed ids are 400 invalid_request")
  void ids() {
    long start = Instant.parse("2026-11-08T10:15:30.123Z").toEpochMilli();
    long txId = ((start - Ids.TX_EPOCH_MS) << 23) | (0xABCL << 11) | 5;
    assertThat(Ids.restoredTime(txId)).isEqualTo(Instant.ofEpochMilli(start));
    assertThat(Ids.parseUnsigned("tx_id", Long.toUnsignedString(txId))).isEqualTo(txId);
    assertThat(Ids.parseUnsigned("tx_id", "18446744073709551615")).isEqualTo(-1L);
    for (String bad :
        new String[] {"", "-1", "18446744073709551616", "12a", " 1", "1".repeat(21)}) {
      assertThatThrownBy(() -> Ids.parseUnsigned("tx_id", bad))
          .isInstanceOf(ApiException.class)
          .hasMessage("invalid_request");
    }
    assertThat(Ids.keyOrNull(0)).isNull();
    assertThat(Ids.keyOrNull(-2)).isEqualTo("18446744073709551614");
    assertThat(Ids.spanHex(0)).isNull();
    assertThat(Ids.spanHex(255)).isEqualTo("00000000000000ff");
  }

  @Test
  @Tag("req:DSN-14-07")
  @DisplayName(
      "trace ids: 32 hex, never all zero; the first 4 bytes are a time hint only when plausible")
  void traceIds() {
    assertThat(Ids.traceId("trace_id", "ABCDEF0123456789abcdef0123456789"))
        .isEqualTo("abcdef0123456789abcdef0123456789");
    assertThatThrownBy(() -> Ids.traceId("trace_id", "0".repeat(32)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Ids.traceId("trace_id", "xyz")).isInstanceOf(ApiException.class);
    Instant now = Instant.parse("2026-11-08T10:00:00Z");
    String argos =
        String.format(Locale.ROOT, "%08x", now.getEpochSecond() - 3600) + "0".repeat(23) + "1";
    assertThat(Ids.traceTimeHint(argos, now.minusSeconds(86_400), now.plusSeconds(86_400)))
        .isEqualTo(now.minusSeconds(3600));
    assertThat(
            Ids.traceTimeHint(
                "00000001" + "0".repeat(23) + "1", now.minusSeconds(86_400), now.plusSeconds(1)))
        .isNull();
  }

  @Test
  @Tag("req:SEC-31")
  @DisplayName(
      "remote_ip filter: IPv4 masked to /24, IPv6 to /48, literals only (a name is refused, never"
          + " resolved); stored addresses shown dotted, '::' (mode off) as null")
  void remoteIps() {
    RemoteIps.Filter v4 = RemoteIps.filter("10.1.2.77");
    assertThat(v4.exact()).isEqualTo("::ffff:10.1.2.77");
    assertThat(v4.masked()).isEqualTo("::ffff:10.1.2.0");
    RemoteIps.Filter v6 = RemoteIps.filter("2001:db8:1:2:3:4:5:6");
    assertThat(v6.masked()).isEqualTo("2001:db8:1:0:0:0:0:0");
    for (String bad : new String[] {"example.org", "10.1.2.256", ".:1", "localhost", "1.2.3"}) {
      assertThatThrownBy(() -> RemoteIps.filter(bad))
          .isInstanceOf(ApiException.class)
          .hasMessage("filter_invalid");
    }
    assertThat(RemoteIps.display("::ffff:10.1.2.0")).isEqualTo("10.1.2.0");
    assertThat(RemoteIps.display("::")).isNull();
    assertThat(RemoteIps.display("2001:db8::1")).isEqualTo("2001:db8::1");
  }

  @Test
  @Tag("req:C-13")
  @Tag("req:SEC-3A")
  @DisplayName(
      "masked_by_role: values become type and length; NULL, [..] and <..> stay; anything"
          + " unreadable is [masked]")
  void bindParams() {
    assertThat(BindParams.mask("1='alice@example.org', 2=42, 3=NULL, 4=true"))
        .isEqualTo("1=[String(17)], 2=[Number(2)], 3=NULL, 4=[Boolean(4)]");
    assertThat(BindParams.mask("1='it''s, a, test', 2=-1.5e3"))
        .isEqualTo("1=[String(13)], 2=[Number(6)]");
    assertThat(BindParams.mask("1=[byte[](16)], 2=<Blob>, 3=[String(4)]"))
        .isEqualTo("1=[byte[](16)], 2=<Blob>, 3=[String(4)]");
    assertThat(BindParams.mask("1='unterminated")).isEqualTo("1=[masked]");
    assertThat(BindParams.mask("free text with secret")).isEqualTo("[masked]");
    assertThat(BindParams.mask("1=abc")).isEqualTo("1=[masked]");
    assertThat(BindParams.mask(null)).isNull();
  }

  @Test
  @Tag("req:C-141")
  @DisplayName(
      "step types are the C-1 numbers; name and target dictionaries per type (proto ProfileStep,"
          + " C-143 (4))")
  void stepTypes() {
    assertThat(TransactionDetailService.typeNumber("SQL")).isEqualTo(1);
    assertThat(TransactionDetailService.typeNumber("SUMMARY")).isEqualTo(11);
    assertThat(TransactionDetailService.typeNumber("NOPE")).isZero();
    assertThat(TransactionDetailService.nameKind(2)).isEqualTo(Kind.POOL);
    assertThat(TransactionDetailService.targetKind(2)).isEqualTo(Kind.DB_TARGET);
    assertThat(TransactionDetailService.nameKind(5)).isEqualTo(Kind.HOST);
    assertThat(TransactionDetailService.nameKind(8)).isEqualTo(Kind.CLASS);
    assertThat(TransactionDetailService.nameKind(9)).isNull();
  }
}
