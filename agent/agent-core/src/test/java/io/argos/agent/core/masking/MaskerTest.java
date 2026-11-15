/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SEC-36 message masking (16 §6.6): default set, user patterns, bounds and adversarial input. */
@Tag("req:SEC-36")
class MaskerTest {
  private final Masker masker = Masker.defaults();

  @AfterEach
  void restoreCurrent() {
    Masker.install(null);
  }

  /**
   * A syntactically valid but meaningless JWT ({"alg":"HS256"}.{"sub":"1234567890"}.signature). It
   * is joined at run time so that the secret scanner (gitleaks jwt rule, SEC-73) does not report a
   * test fixture as a leaked token; the masker still sees the complete three-part token.
   */
  private static String sampleJwt() {
    return String.join(
        ".", "eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiIxMjM0NTY3ODkwIn0", "dozjgNryP4J3jVmNHl0w5N");
  }

  /** The seed sentinels of 18 §4.3 (ADR-0004 C-44): ARGOS_SEED and ARGOSSEED. */
  private static final String SENTINEL = "ARGOS" + "_SEED";

  private static final String ACCESS_KEY_LIKE = "AKIA" + "ARGOSSEED0000001";

  static Stream<Arguments> defaultSet() {
    return Stream.of(
        // email
        Arguments.of(
            "email", "order 42 not found for kim@example.test", "order 42 not found for <email>"),
        Arguments.of(
            "email with + and subdomains",
            "notify a.b+tag@mail.example.co.kr failed",
            "notify <email> failed"),
        Arguments.of(
            "email seed of SeedService",
            "contact lookup failed for " + SENTINEL + ".kim@example.test",
            "contact lookup failed for <email>"),
        Arguments.of(
            "16 §6.5 email in a SQL comment", "/* kim@x.com */ select 1", "/* <email> */ select 1"),
        Arguments.of(
            "16 §6.5 Statement inline email", "WHERE email='kim@x.com'", "WHERE email='<email>'"),
        Arguments.of(
            "16 §6.5 a@b.c: one-letter TLD is no email under 16 §6.6",
            "WHERE email='a@b.c'",
            "WHERE email='a@b.c'"),
        // kr_mobile
        Arguments.of("mobile with dashes", "call 010-1234-5678 now", "call <phone> now"),
        Arguments.of("mobile without separators", "01098765432", "<phone>"),
        Arguments.of("mobile with spaces, 3-digit middle", "011 234 5678", "<phone>"),
        Arguments.of(
            "phone pattern seed",
            "payment declined for phone 010-9999-0001",
            "payment declined for phone <phone>"),
        Arguments.of(
            "16 §6.5 JSON literal", "'{\"phone\":\"010-1234-5678\"}'", "'{\"phone\":\"<phone>\"}'"),
        Arguments.of("16 §6.5 LIKE '%010%'", "LIKE '%010%'", "LIKE '%010%'"),
        Arguments.of(
            "Korean text around a mobile number",
            "고객 홍길동 010-1234-5678 결제 실패",
            "고객 홍길동 <phone> 결제 실패"),
        // kr_rrn
        Arguments.of("resident number", "rrn 900101-1234567", "rrn <rrn>"),
        Arguments.of("resident number pattern seed", "990101-1234567", "<rrn>"),
        Arguments.of(
            "resident number without dash", "id 9001012234567 rejected", "id <rrn> rejected"),
        // card
        Arguments.of(
            "card with spaces", "card 4111 1111 1111 1111 declined", "card <card> declined"),
        Arguments.of("card pattern seed", "card 4111111111111111", "card <card>"),
        Arguments.of("card with dashes", "5500-0000-0000-0004 ok", "<card> ok"),
        Arguments.of("15-digit card", "amex 378282246310005", "amex <card>"),
        Arguments.of(
            "Luhn failure stays", "order 4111111111111112 failed", "order 4111111111111112 failed"),
        Arguments.of("short numbers stay", "order 42 not found", "order 42 not found"),
        // ipv4
        Arguments.of("IPv4", "from 192.168.10.20 refused", "from <ip> refused"),
        Arguments.of(
            "IPv4 in a URL", "connect to http://10.0.0.5:8080/x", "connect to http://<ip>:8080/x"),
        Arguments.of("three-part version stays", "spring 5.3.27 started", "spring 5.3.27 started"),
        // jwt
        Arguments.of("JWT", "token " + sampleJwt(), "token <jwt>"),
        Arguments.of(
            "JWT after Bearer",
            "Authorization: Bearer " + sampleJwt() + ".",
            "Authorization: Bearer <jwt>."),
        Arguments.of(
            "dotted class names are no JWT",
            "No bean com.example.LongPackageName.AnotherLongName.Klass",
            "No bean com.example.LongPackageName.AnotherLongName.Klass"),
        // mixed and plain
        Arguments.of(
            "every kind in one message",
            "user kim@example.test from 192.168.0.1 card 4111111111111111 phone 010-1234-5678 rrn 900101-1234567 t "
                + sampleJwt(),
            "user <email> from <ip> card <card> phone <phone> rrn <rrn> t <jwt>"),
        Arguments.of(
            "plain text stays", "Connection refused: connect", "Connection refused: connect"),
        Arguments.of("empty stays", "", ""));
  }

  @ParameterizedTest(name = "T-SEC-36 default set: {0}")
  @MethodSource("defaultSet")
  void defaultSetMasks(String name, String input, String expected) {
    assertThat(masker.mask(input)).isEqualTo(expected);
  }

  @Test
  @DisplayName("T-SEC-36 the default set is the 16 §6.6 table in its order")
  void tableOrder() {
    List<String> names = new ArrayList<String>();
    for (MaskPatterns.Rule rule : MaskPatterns.defaults()) {
      names.add(rule.name() + "=" + rule.replacement());
    }
    assertThat(names)
        .containsExactly(
            "email=<email>",
            "kr_mobile=<phone>",
            "kr_rrn=<rrn>",
            "card=<card>",
            "ipv4=<ip>",
            "jwt=<jwt>");
    assertThat(masker.mask(null)).isNull();
  }

  @Test
  @DisplayName("Luhn: 13-19 digits, separators ignored")
  void luhn() {
    assertThat(Masker.luhn("4111-1111-1111-1111")).isTrue();
    assertThat(Masker.luhn("4111111111111112")).isFalse();
    assertThat(Masker.luhn("0000000000")).isFalse();
    assertThat(Masker.luhn("378282246310005")).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-36 seeds with the ARGOS_SEED/ARGOSSEED sentinels: shapes are masked, a user pattern"
          + " masks the rest")
  void seedSentinels() {
    String phone = "payment declined for phone 010-" + SENTINEL + "-0001";
    String card = "card 4111-1111-" + SENTINEL + " declined (pattern 4111111111111111)";
    String jwtLike = "token eyJhbGciOi." + SENTINEL + ".sig";
    String key = "api key " + ACCESS_KEY_LIKE;
    String forwarded = "client 203.0.113." + SENTINEL;
    // The default set masks PII shapes; seed values that only carry the sentinel are no shape.
    assertThat(masker.mask("contact lookup failed for " + SENTINEL + ".kim@example.test"))
        .doesNotContain(SENTINEL);
    assertThat(masker.mask(card))
        .isEqualTo("card 4111-1111-" + SENTINEL + " declined (pattern <card>)");
    assertThat(masker.mask(phone)).isEqualTo(phone);
    assertThat(masker.mask(jwtLike)).isEqualTo(jwtLike);
    assertThat(masker.mask(key)).isEqualTo(key);
    // The operator's capture.mask-patterns (user patterns) close that gap for both sentinels.
    List<String> warnings = new ArrayList<String>();
    Masker withUser =
        Masker.of(Collections.singletonList("[^\\s()]*ARGOS_?SEED[^\\s()]*"), warnings);
    assertThat(warnings).isEmpty();
    assertThat(withUser.userPatternCount()).isEqualTo(1);
    for (String text : Arrays.asList(phone, card, jwtLike, key, forwarded)) {
      String masked = withUser.mask(text);
      assertThat(masked).as(text).doesNotContain(SENTINEL).doesNotContain("ARGOSSEED");
      assertThat(masked).contains(MaskPatterns.USER_REPLACEMENT);
    }
    assertThat(withUser.mask(card)).isEqualTo("card <masked> declined (pattern <card>)");
  }

  @Test
  @DisplayName("T-SEC-36 user patterns: after the defaults, invalid skipped, 32 x 512 at most")
  void userPatterns() {
    List<String> warnings = new ArrayList<String>();
    Masker user =
        Masker.of(
            Arrays.asList("ORD-\\d{6}", "(unclosed", "", repeat('x', 513), "cust-[0-9]+", "x*"),
            warnings);
    assertThat(user.userPatternCount()).isEqualTo(3);
    assertThat(warnings)
        .containsExactly(
            "capture.mask-patterns pattern 2 is not a valid regular expression; skipped",
            "capture.mask-patterns pattern 4 is longer than 512 characters; skipped");
    assertThat(user.mask("order ORD-123456 of cust-42 by kim@example.test"))
        .isEqualTo("order <masked> of <masked> by <email>");
    // an empty match ("x*") replaces nothing
    assertThat(user.mask("abc")).isEqualTo("abc");

    List<String> many = new ArrayList<String>();
    for (int i = 0; i < 40; i++) {
      many.add("pat-" + i + "-end");
    }
    List<String> capWarnings = new ArrayList<String>();
    Masker capped = Masker.of(many, capWarnings);
    assertThat(capped.userPatternCount()).isEqualTo(MaskPatterns.MAX_USER_PATTERNS);
    assertThat(capWarnings)
        .containsExactly("capture.mask-patterns has more than 32 patterns; 8 ignored");
    assertThat(capped.mask("pat-31-end pat-32-end")).isEqualTo("<masked> pat-32-end");

    assertThat(Masker.of(Collections.<String>emptyList(), warnings)).isSameAs(Masker.defaults());
    assertThat(Masker.of(null, warnings)).isSameAs(Masker.defaults());
  }

  @Test
  @DisplayName("install/current: the masker of the current configuration, defaults otherwise")
  void current() {
    assertThat(Masker.current()).isSameAs(Masker.defaults());
    Masker user = Masker.of(Collections.singletonList("secret-\\d+"), new ArrayList<String>());
    Masker.install(user);
    assertThat(Masker.current()).isSameAs(user);
    assertThat(Masker.current().mask("x secret-1")).isEqualTo("x <masked>");
    Masker.install(null);
    assertThat(Masker.current()).isSameAs(Masker.defaults());
  }

  @Test
  @DisplayName("T-SEC-36 the input is cut to 64 KiB before matching")
  void inputCap() {
    String head = "mail kim@example.test ";
    String text = head + repeat('x', Masker.MAX_INPUT_CHARS) + " tail lee@example.test";
    String masked = masker.mask(text);
    assertThat(masked).startsWith("mail <email> ").doesNotContain("lee@");
    assertThat(masked.length())
        .isEqualTo(Masker.MAX_INPUT_CHARS - head.length() + "mail <email> ".length());
  }

  @Test
  @DisplayName("T-SEC-36 the 1,024-character limit applies after masking; pairs are not split")
  void messageLimitAfterMasking() {
    StringBuilder emails = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      emails.append("user").append(i).append("@example.test ");
    }
    assertThat(emails.length()).isGreaterThan(Masker.DEFAULT_MESSAGE_MAX);
    String masked = masker.maskMessage(emails.toString(), Masker.DEFAULT_MESSAGE_MAX);
    assertThat(masked).doesNotContain("@").startsWith("<email> <email> ");
    assertThat(masked.length()).isEqualTo(800);

    assertThat(masker.maskMessage(repeat('x', 5000), 1024)).hasSize(1024);
    assertThat(masker.maskMessage(repeat('x', 5000), -1)).hasSize(Masker.DEFAULT_MESSAGE_MAX);
    String pair = repeat('x', 1023) + "😀tail";
    assertThat(masker.maskMessage(pair, 1024)).hasSize(1023);
    assertThat(masker.maskMessage(null, 10)).isNull();
    assertThat(masker.maskMessage("call 010-1234-5678", 8)).isEqualTo("call <ph");
  }

  @Test
  @DisplayName("T-SEC-36 64 KiB of adversarial input is masked in linear time by the default set")
  void adversarialDefaultSet() {
    int n = Masker.MAX_INPUT_CHARS;
    List<String> inputs =
        Arrays.asList(
            repeat('a', n),
            cycle("a.", n),
            cycle("a@", n),
            "x@" + cycle("a.", n - 3) + "!",
            cycle("1 ", n),
            repeat('1', n),
            repeat('0', n),
            cycle("1.", n),
            cycle("010-", n),
            "eyJ" + repeat('a', n - 3),
            cycle("eyJaaaaaaaaaa.", n),
            cycle("-_", n),
            cycle("a%+", n));
    long start = System.nanoTime();
    for (String input : inputs) {
      assertThat(masker.mask(input)).isNotNull();
    }
    long millis = (System.nanoTime() - start) / 1_000_000;
    // Linear rules finish in a few ms per input; quadratic ones would take minutes at 64 KiB.
    assertThat(millis).as("ms for %d inputs of 64 KiB", inputs.size()).isLessThan(3000);
  }

  @Test
  @DisplayName("T-SEC-36 a catastrophic user pattern stops at its budget and masks everything")
  void userPatternBudget() {
    Masker user = Masker.of(Collections.singletonList("(a+)+b"), new ArrayList<String>());
    long before = Masker.budgetExceededTotal();
    long start = System.nanoTime();
    String result = user.mask("kim@example.test " + repeat('a', 40) + "!");
    long millis = (System.nanoTime() - start) / 1_000_000;
    assertThat(result).isEqualTo(Masker.BUDGET_EXCEEDED);
    assertThat(Masker.budgetExceededTotal()).isEqualTo(before + 1);
    assertThat(millis).isLessThan(1000);
    // a linear user pattern on 64 KiB stays inside its budget
    Masker linear = Masker.of(Collections.singletonList("secret-\\d+"), new ArrayList<String>());
    String big = cycle("secret-1 x ", Masker.MAX_INPUT_CHARS);
    assertThat(linear.mask(big)).doesNotContain("secret-1").contains("<masked>");
    assertThat(Masker.budgetExceededTotal()).isEqualTo(before + 1);
  }

  static Stream<Arguments> recursivePatterns() {
    return Stream.of(
        Arguments.of("(?:a|b)*c", cycle("ab", Masker.MAX_INPUT_CHARS)),
        Arguments.of("([a-z]|-)+@corp", cycle("a-", Masker.MAX_INPUT_CHARS)));
  }

  /**
   * A repeated group with an alternation recurses once per repetition in java.util.regex: on 64 KiB
   * it overflows the stack long before the read budget runs out. The masker must fail closed as for
   * the budget (16 §6.6, SEC-36): the whole text masked and counted, nothing thrown to the caller.
   * Runs on a new thread with the default stack size so that the result does not depend on how deep
   * the test runner's stack already is.
   */
  @ParameterizedTest(
      name = "T-SEC-36 a user pattern that overflows the stack ({0}) fails closed and is counted")
  @MethodSource("recursivePatterns")
  void userPatternStackOverflowFailsClosed(String pattern, String input) throws Exception {
    List<String> warnings = new ArrayList<String>();
    final Masker user = Masker.of(Collections.singletonList(pattern), warnings);
    assertThat(warnings).isEmpty();
    assertThat(user.userPatternCount()).isEqualTo(1);
    final String text = "kim@example.test " + input;
    final AtomicReference<String> result = new AtomicReference<String>();
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    long before = Masker.budgetExceededTotal();
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  result.set(user.mask(text));
                } catch (Throwable t) {
                  thrown.set(t);
                }
              }
            },
            "masker-stack-test");
    thread.start();
    thread.join(60_000);
    assertThat(thread.isAlive()).isFalse();
    assertThat(thrown.get()).as("thrown by mask()").isNull();
    assertThat(result.get()).isEqualTo(Masker.BUDGET_EXCEEDED);
    assertThat(Masker.budgetExceededTotal()).isEqualTo(before + 1);
  }

  static Stream<Arguments> cardCandidates() {
    return Stream.of(
        // a 16-digit card followed by another digit group: the shorter candidate passes Luhn
        Arguments.of("4111 1111 1111 1111 1 item", "<card> 1 item"),
        Arguments.of("4111111111111111 2", "<card> 2"),
        Arguments.of("4111-1111-1111-1111-5", "<card>-5"),
        Arguments.of("paid 5500 0000 0000 0004 12 times", "paid <card> 12 times"),
        Arguments.of("amex 378282246310005 7 ok", "amex <card> 7 ok"),
        // no candidate of 13-19 digits passes Luhn: the digits stay
        Arguments.of("order 4111111111111112 failed", "order 4111111111111112 failed"),
        Arguments.of("ids 1234 5678 9012 3456 7", "ids 1234 5678 9012 3456 7"),
        Arguments.of("4111111111111111111", "4111111111111111111"),
        // a candidate is cut only before a separator, never inside a digit group
        Arguments.of("41111111111111111", "41111111111111111"));
  }

  @ParameterizedTest(name = "T-SEC-36 card: ''{0}'' -> ''{1}''")
  @MethodSource("cardCandidates")
  void cardShorterCandidates(String input, String expected) {
    assertThat(masker.mask(input)).isEqualTo(expected);
  }

  private static String repeat(char c, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(c);
    }
    return out.toString();
  }

  private static String cycle(String unit, int length) {
    StringBuilder out = new StringBuilder(length);
    while (out.length() < length) {
      out.append(unit);
    }
    out.setLength(length);
    return out.toString();
  }
}
