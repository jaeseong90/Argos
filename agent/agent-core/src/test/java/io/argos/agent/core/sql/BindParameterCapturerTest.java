/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.core.masking.Masker;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * BindParameterCapturer (F-A04, SEC-3A, 16 §6.7): the three modes, the type whitelist, no call on
 * streams, LOBs or other objects, the masker, 256 characters per value, 64 indexes, the first
 * parameter set of a batch, the {@code col = ?} column mapping of {@code partial}.
 */
@Tag("req:F-A04")
@Tag("req:SEC-3A")
class BindParameterCapturerTest {
  private static final String SQL =
      "select * from member m where m.email = ? and phone=? and name like ? and \"Grade\" = ?";

  private static CaptureConfig mode(int mode, String... whitelist) {
    return CaptureConfig.builder().sqlParamMode(mode).sqlParamWhitelistColumns(whitelist).build();
  }

  private static void capture(
      SqlState state, int index, Object value, String setter, CaptureConfig c) {
    BindParameterCapturer.capture(
        state,
        index,
        value,
        setter,
        c,
        Masker.defaults(),
        SqlNormalizer.defaults(),
        SqlDialect.ANSI);
  }

  @Test
  @DisplayName(
      "T-AG-033b masked mode keeps nothing; full keeps every value after the masker; partial keeps"
          + " whitelisted 'col = ?' positions and the type and length of the others")
  void modes() {
    SqlState masked = new SqlState(SQL, 0L);
    capture(masked, 1, "kim@example.test", "setString", mode(CaptureConfig.SQL_PARAMS_MASKED));
    assertThat(masked.paramCount()).isZero();
    assertThat(masked.paramDetail()).isNull();

    SqlState full = new SqlState(SQL, 0L);
    CaptureConfig fullMode = mode(CaptureConfig.SQL_PARAMS_FULL);
    capture(full, 1, "kim@example.test", "setString", fullMode);
    capture(full, 2, "010-1234-5678", "setString", fullMode);
    capture(full, 3, "%Lee%", "setString", fullMode);
    capture(full, 4, 3, "setInt", fullMode);
    assertThat(full.paramDetail()).isEqualTo("1='<email>', 2='<phone>', 3='%Lee%', 4=3");

    SqlState partial = new SqlState(SQL, 0L);
    CaptureConfig partialMode = mode(CaptureConfig.SQL_PARAMS_PARTIAL, "EMAIL", "member.grade");
    capture(partial, 1, "kim@example.test", "setString", partialMode);
    capture(partial, 2, "010-1234-5678", "setString", partialMode);
    capture(partial, 3, "%Lee%", "setString", partialMode);
    capture(partial, 4, 3, "setInt", partialMode);
    assertThat(partial.paramWhitelistKnown).isTrue();
    assertThat(partial.paramWhitelist).isEqualTo(0b1001L);
    assertThat(partial.paramDetail())
        .as("email is whitelisted but still masked; phone and name are not whitelisted")
        .isEqualTo("1='<email>', 2=[String(13)], 3=[String(5)], 4=3")
        .doesNotContain("Lee")
        .doesNotContain("1234");
  }

  @Test
  @DisplayName(
      "T-AG-033b the partial column mapping reads 'col = ?' only, counts ? outside literals and"
          + " comments, and survives IN lists")
  void whitelistMask() {
    String[] columns = {"status", "region"};
    assertThat(
            BindParameterCapturer.whitelistMask(
                "select * from t where id in (?, ?, ?) and t.status = ? and region=?"
                    + " and note = '?' /* ? */ and x = ?",
                SqlDialect.ANSI,
                columns,
                null))
        .as("positions 4 and 5")
        .isEqualTo(0b11000L);
    assertThat(
            BindParameterCapturer.whitelistMask(
                "update t set status = ? where region <> ? and region >= ?",
                SqlDialect.ANSI,
                columns,
                null))
        .as("only '=' maps a column")
        .isEqualTo(0b1L);
    assertThat(BindParameterCapturer.whitelistMask(null, 0, columns, null)).isZero();
    assertThat(BindParameterCapturer.whitelistMask("select ?", 0, new String[0], null)).isZero();
    assertThat(
            BindParameterCapturer.whitelistMask(
                "select \"a from t where status = ?", SqlDialect.POSTGRESQL, columns, null))
        .as("unreadable statement: everything masked")
        .isZero();
  }

  @Test
  @DisplayName(
      "T-AG-033b the type whitelist: JDK values rendered, byte[] by length, anything else by its"
          + " type name only; null and setNull are NULL")
  void typeWhitelist() {
    Masker masker = Masker.defaults();
    assertThat(BindParameterCapturer.plain("abc", "setString", masker)).isEqualTo("'abc'");
    assertThat(BindParameterCapturer.plain("O'Brien", "setString", masker)).isEqualTo("'O''Brien'");
    assertThat(BindParameterCapturer.plain(42L, "setLong", masker)).isEqualTo("42");
    assertThat(BindParameterCapturer.plain(new BigDecimal("12.50"), "setBigDecimal", masker))
        .isEqualTo("12.50");
    assertThat(BindParameterCapturer.plain(true, "setBoolean", masker)).isEqualTo("true");
    assertThat(
            BindParameterCapturer.plain(
                Timestamp.valueOf("2026-01-02 03:04:05"), "setTimestamp", masker))
        .isEqualTo("'2026-01-02 03:04:05.0'");
    assertThat(BindParameterCapturer.plain(LocalDate.of(2026, 1, 2), "setObject", masker))
        .isEqualTo("'2026-01-02'");
    UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    assertThat(BindParameterCapturer.plain(uuid, "setObject", masker)).isEqualTo("'" + uuid + "'");
    assertThat(BindParameterCapturer.plain(new byte[16], "setBytes", masker))
        .isEqualTo("[byte[](16)]");
    assertThat(BindParameterCapturer.typeAndLength(new byte[3], "setBytes"))
        .isEqualTo("[byte[](3)]");
    assertThat(BindParameterCapturer.plain(null, "setObject", masker)).isEqualTo("NULL");
    assertThat(BindParameterCapturer.plain(12, "setNull", masker))
        .as("setNull(i, sqlType)")
        .isEqualTo("NULL");
    assertThat(BindParameterCapturer.typeAndLength("hello world", "setString"))
        .isEqualTo("[String(11)]");
    assertThat(BindParameterCapturer.typeAndLength(12345, "setInt")).isEqualTo("[Integer(5)]");
    assertThat(BindParameterCapturer.plain('c', "setObject", masker)).isEqualTo("<Character>");
    assertThat(BindParameterCapturer.plain(new int[] {1}, "setObject", masker))
        .isEqualTo("<array>");
    // Application objects, even subclasses of whitelisted JDK types, are never rendered.
    assertThat(BindParameterCapturer.plain(new Entity(), "setObject", masker))
        .isEqualTo("<Entity>");
    assertThat(BindParameterCapturer.plain(new AppDate(), "setDate", masker))
        .isEqualTo("<AppDate>");
    assertThat(BindParameterCapturer.plain(new AppNumber(), "setObject", masker))
        .isEqualTo("<AppNumber>");
    assertThat(Entity.calls.get()).as("Entity.toString() calls").isZero();
    BigInteger huge = BigInteger.ONE.shiftLeft(BindParameterCapturer.MAX_BIG_BITS + 1);
    assertThat(BindParameterCapturer.plain(huge, "setObject", masker)).isEqualTo("<BigInteger>");
  }

  @Test
  @DisplayName(
      "T-AG-033b streams, readers and LOBs are never touched: recording proxies see zero calls in"
          + " every mode")
  void noLobOrStreamAccess() {
    List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    Blob blob = recording(Blob.class, calls);
    Clob clob = recording(Clob.class, calls);
    java.sql.Array array = recording(java.sql.Array.class, calls);
    RecordingStream stream = new RecordingStream(calls);
    RecordingReader reader = new RecordingReader(calls);
    for (int mode :
        new int[] {
          CaptureConfig.SQL_PARAMS_MASKED,
          CaptureConfig.SQL_PARAMS_PARTIAL,
          CaptureConfig.SQL_PARAMS_FULL
        }) {
      SqlState state = new SqlState("insert into doc (a, b, c, d, e) values (?, ?, ?, ?, ?)", 0L);
      CaptureConfig config = mode(mode, "a", "b", "c", "d", "e");
      capture(state, 1, blob, "setBlob", config);
      capture(state, 2, clob, "setClob", config);
      capture(state, 3, array, "setArray", config);
      capture(state, 4, stream, "setBinaryStream", config);
      capture(state, 5, reader, "setCharacterStream", config);
      if (mode == CaptureConfig.SQL_PARAMS_FULL) {
        assertThat(state.param(1)).startsWith("<Proxy");
        assertThat(state.param(4)).isEqualTo("<RecordingStream>");
        assertThat(state.param(5)).isEqualTo("<RecordingReader>");
      }
    }
    assertThat(calls).as("calls on streams, readers and LOBs").isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-033b bounds: 256 characters per value after masking, indexes 1..64, the first set of"
          + " a batch only")
  void bounds() {
    CaptureConfig fullMode = mode(CaptureConfig.SQL_PARAMS_FULL);
    SqlState state = new SqlState("select ?", 0L);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      text.append('v');
    }
    text.append(" kim@example.test");
    capture(state, 1, text.toString(), "setString", fullMode);
    assertThat(state.param(1)).hasSize(SqlState.MAX_VALUE_CHARS).doesNotContain("@");
    capture(state, 0, "zero", "setString", fullMode);
    capture(state, 65, "65", "setString", fullMode);
    capture(state, 64, "64", "setString", fullMode);
    assertThat(state.param(64)).isEqualTo("'64'");
    assertThat(state.paramCount()).isEqualTo(64);
    // The masker sees the value before it is cut: an email across position 256 never survives in
    // part either.
    StringBuilder edge = new StringBuilder();
    for (int i = 0; i < 245; i++) {
      edge.append('a');
    }
    edge.append(" kim.argos_seed@example.test");
    SqlState masked = new SqlState("select ?", 0L);
    capture(masked, 1, edge.toString(), "setString", fullMode);
    assertThat(masked.param(1)).doesNotContain("kim.argos").doesNotContain("argos_seed");

    SqlState batch = new SqlState("insert into t values (?)", 0L);
    capture(batch, 1, "first", "setString", fullMode);
    batch.addBatch();
    capture(batch, 1, "second", "setString", fullMode);
    batch.addBatch();
    assertThat(batch.paramDetail()).isEqualTo("1='first'");
    assertThat(batch.endBatch()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-AG-033c a value is masked whole and cut after masking: a phone, RRN or card number at"
          + " characters 500-519 behind 14 e-mail addresses never survives in part (full and a"
          + " whitelisted partial column)")
  void maskBeforeCutAtAnyPosition() {
    String[] tokens = {"010-1234-5678", "900101-1234567", "4111 1111 1111 1111"};
    String[] placeholders = {"<phone>", "<rrn>", "<card>"};
    CaptureConfig fullMode = mode(CaptureConfig.SQL_PARAMS_FULL);
    CaptureConfig partialMode = mode(CaptureConfig.SQL_PARAMS_PARTIAL, "note");
    Pattern digitRun = Pattern.compile("\\d{6,}");
    for (int t = 0; t < tokens.length; t++) {
      String value = behindEmails(tokens[t], 500);
      assertThat(value.indexOf(tokens[t])).isEqualTo(500);
      for (CaptureConfig config : new CaptureConfig[] {fullMode, partialMode}) {
        SqlState state = new SqlState("update member set note = ? where id = 7", 0L);
        capture(state, 1, value, "setString", config);
        String kept = state.param(1);
        String what = tokens[t] + " in mode " + config.sqlParamMode();
        assertThat(kept).as(what).hasSizeLessThanOrEqualTo(SqlState.MAX_VALUE_CHARS);
        assertThat(kept)
            .as(what)
            .doesNotContain("010-1234")
            .doesNotContain("900101-1234")
            .doesNotContain("4111")
            .doesNotContain("1234")
            .doesNotContain("argos_seed");
        assertThat(digitRun.matcher(kept).find())
            .as("6+ digit run in %s: %s", what, kept)
            .isFalse();
        // 14 '<email> ' and the filler fit in 256 characters, so the token's placeholder is kept.
        assertThat(kept).as(what).contains(placeholders[t]);
      }
    }
  }

  /**
   * 14 e-mail addresses of 30 characters (each one shrinks by 23 when masked), letters up to {@code
   * at - 1}, a space, {@code token} at {@code at}, and a tail: before the fix the value was cut at
   * 512 characters before masking, which split {@code token} and moved its first part into the 256
   * kept characters.
   */
  private static String behindEmails(String token, int at) {
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < 14; i++) {
      value.append("seed").append((char) ('a' + i)).append('x').append(".argos_seed@example.test ");
    }
    while (value.length() < at - 1) {
      value.append('w');
    }
    value.append(' ').append(token).append(" and some more text after the value");
    return value.toString();
  }

  @SuppressWarnings("unchecked")
  private static <T> T recording(Class<T> type, final List<String> calls) {
    return (T)
        Proxy.newProxyInstance(
            BindParameterCapturerTest.class.getClassLoader(),
            new Class<?>[] {type},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) {
                calls.add(method.getName());
                return null;
              }
            });
  }

  static final class RecordingStream extends InputStream {
    private final List<String> calls;

    RecordingStream(List<String> calls) {
      this.calls = calls;
    }

    @Override
    public int read() {
      calls.add("read");
      return -1;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      calls.add("read");
      return -1;
    }

    @Override
    public String toString() {
      calls.add("toString");
      return "stream";
    }

    @Override
    public boolean equals(Object other) {
      calls.add("equals");
      return this == other;
    }

    @Override
    public int hashCode() {
      calls.add("hashCode");
      return 1;
    }
  }

  static final class RecordingReader extends Reader {
    private final List<String> calls;

    RecordingReader(List<String> calls) {
      this.calls = calls;
    }

    @Override
    public int read(char[] buffer, int offset, int length) {
      calls.add("read");
      return -1;
    }

    @Override
    public void close() {
      calls.add("close");
    }

    @Override
    public String toString() {
      calls.add("toString");
      return "reader";
    }
  }

  static final class Entity {
    static final AtomicInteger calls = new AtomicInteger();

    @Override
    public String toString() {
      calls.incrementAndGet();
      return "secret entity";
    }
  }

  static final class AppDate extends java.util.Date {
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
      return "secret date";
    }
  }

  static final class AppNumber extends Number {
    private static final long serialVersionUID = 1L;

    @Override
    public int intValue() {
      return 1;
    }

    @Override
    public long longValue() {
      return 1;
    }

    @Override
    public float floatValue() {
      return 1;
    }

    @Override
    public double doubleValue() {
      return 1;
    }

    @Override
    public String toString() {
      return "secret number";
    }
  }
}
