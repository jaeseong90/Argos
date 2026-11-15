/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The final bootstrap API of WO-10 (10 §3): every numeric constant is pinned, so that a changed or
 * added value fails here before it reaches the protocol or ClickHouse (C-1, C-16, C-69).
 */
class BootstrapContractTest {

  @AfterEach
  void restoreCapture() {
    new AgentControl().setCapture(null);
  }

  @Test
  @Tag("req:C-1")
  @Tag("req:C-16")
  @DisplayName("every int constant of the bootstrap tables keeps its number (C-1, C-16, C-69)")
  void constantNumbersArePinned() throws IllegalAccessException {
    assertThat(constants(StepType.class))
        .isEqualTo(
            map(
                "SQL",
                1,
                "DBC",
                2,
                "COMMIT",
                3,
                "ROLLBACK",
                4,
                "HTTP",
                5,
                "METHOD",
                6,
                "ASYNC",
                7,
                "ERROR",
                8,
                "STACK",
                9,
                "MESSAGE",
                10,
                "SUMMARY",
                11));
    assertThat(constants(DictKind.class))
        .isEqualTo(
            map(
                "SERVICE",
                1,
                "SQL",
                2,
                "CLASS",
                3,
                "METHOD",
                4,
                "THREAD",
                5,
                "HOST",
                6,
                "FRAME",
                7,
                "METRIC",
                8,
                "ERROR_GROUP",
                9,
                "DB_TARGET",
                10,
                "APP_VERSION",
                11,
                "POOL",
                12,
                "COUNT",
                13));
    assertThat(constants(Kind.class)).isEqualTo(map("HTTP", 1, "BATCH", 2, "MESSAGE", 3));
    assertThat(constants(ModuleHealth.class))
        .isEqualTo(
            map(
                "MAX_MODULES",
                64,
                "ENABLED",
                0,
                "DISABLED_BY_CONFIG",
                1,
                "DISABLED_BY_ERRORS",
                2,
                "DISABLED_BY_REMOTE",
                3,
                "TEST_FAULT",
                9));
    assertThat(constants(FailureKind.class))
        .isEqualTo(map("NONE", 0, "TIMEOUT", 1, "CONNECT_FAIL", 2, "HTTP_5XX", 3, "OTHER", 4));
    assertThat(constants(CallDepth.class))
        .isEqualTo(
            map(
                "JDBC_STATEMENT",
                0,
                "JDBC_CONNECTION",
                1,
                "JDBC_DATASOURCE",
                2,
                "HTTP_CLIENT",
                3,
                "SERVLET",
                4,
                "EXECUTOR",
                5,
                "LOGGING",
                6,
                "KEYS",
                8));
    assertThat(constants(TraceContext.class))
        .isEqualTo(
            map("FLAG_VIRTUAL_THREAD", 1, "FLAG_LEAKED", 2, "FLAG_SUMMARIZED", 4, "FLAG_MASK", 7));
    assertThat(constants(CaptureConfig.class))
        .isEqualTo(
            map(
                "QUERY_STRING_OFF",
                0,
                "QUERY_STRING_WHITELIST",
                1,
                "QUERY_STRING_FULL",
                2,
                "REMOTE_IP_MASKED",
                0,
                "REMOTE_IP_FULL",
                1,
                "REMOTE_IP_OFF",
                2,
                "USER_ID_OFF",
                0,
                "USER_ID_PLAIN",
                1,
                "USER_ID_HASHED",
                2,
                "SQL_PARAMS_MASKED",
                0,
                "SQL_PARAMS_PARTIAL",
                1,
                "SQL_PARAMS_FULL",
                2,
                "USER_ID_MAX_LENGTH",
                128,
                "DEFAULT_ERROR_MESSAGE_MAX",
                1024,
                "DEFAULT_STACK_DEPTH",
                30));
    assertThat(ModuleHealth.stateName(ModuleHealth.DISABLED_BY_REMOTE))
        .isEqualTo("DISABLED_BY_REMOTE");
  }

  @Test
  @Tag("req:F-A30")
  @DisplayName(
      "TraceContext flags: virtual thread bit at begin, bits only added, reserved bits stay 0")
  void traceContextFlags() throws InterruptedException {
    TraceContext platform = context(false);
    assertThat(platform.flags()).isZero();
    TraceContext virtual = context(true);
    assertThat(virtual.flags()).isEqualTo(TraceContext.FLAG_VIRTUAL_THREAD);
    assertThat(virtual.hasFlags(TraceContext.FLAG_VIRTUAL_THREAD)).isTrue();

    platform.addFlags(TraceContext.FLAG_LEAKED | 0x100);
    assertThat(platform.flags()).isEqualTo(TraceContext.FLAG_LEAKED);
    platform.addFlags(0);
    assertThat(platform.flags()).isEqualTo(TraceContext.FLAG_LEAKED);

    // Concurrent adds of different bits never lose one (CAS loop, no lock).
    final TraceContext shared = context(false);
    final CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < 8; i++) {
      final int bit = i % 2 == 0 ? TraceContext.FLAG_LEAKED : TraceContext.FLAG_SUMMARIZED;
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  shared.addFlags(bit);
                }
              });
      thread.start();
      threads.add(thread);
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(shared.flags()).isEqualTo(TraceContext.FLAG_LEAKED | TraceContext.FLAG_SUMMARIZED);
    assertThat(shared.cpuStartNanos).isEqualTo(-1);
    assertThat(shared.allocStartBytes).isEqualTo(-1);
  }

  @Test
  @Tag("req:SEC-32")
  @DisplayName("CaptureConfig carries every capture key and is swapped through AgentControl")
  void captureSnapshot() {
    assertThat(CaptureConfig.current()).isSameAs(CaptureConfig.DEFAULT);
    assertThat(NoopTracer.INSTANCE.capture()).isSameAs(CaptureConfig.DEFAULT);
    CaptureConfig defaults = CaptureConfig.DEFAULT;
    assertThat(defaults.queryStringWhitelist()).isEmpty();
    assertThat(defaults.userIdSource()).isNull();
    assertThat(defaults.userIdMaxLength()).isEqualTo(128);
    assertThat(defaults.asyncStep()).isFalse();

    String[] whitelist = {"page"};
    CaptureConfig custom =
        CaptureConfig.builder()
            .queryStringMode(CaptureConfig.QUERY_STRING_WHITELIST)
            .queryStringWhitelist(whitelist)
            .headerWhitelist(new String[] {"x-request-id"})
            .remoteIpMode(CaptureConfig.REMOTE_IP_FULL)
            .trustedProxies(new String[] {"10.0.0.0/8"})
            .userIdMode(CaptureConfig.USER_ID_HASHED)
            .userIdSource("header:x-user")
            .userIdMaxLength(64)
            .sqlParamMode(CaptureConfig.SQL_PARAMS_PARTIAL)
            .sqlParamWhitelistColumns(new String[] {"status"})
            .errorMessageMax(512)
            .stackDepth(10)
            .asyncStep(true)
            .build();
    whitelist[0] = "changed";
    assertThat(custom.queryStringWhitelist()).containsExactly("page");
    assertThat(custom.userIdSource()).isEqualTo("header:x-user");
    assertThat(custom.userIdMaxLength()).isEqualTo(64);
    assertThat(custom.sqlParamWhitelistColumns()).containsExactly("status");
    assertThat(custom.asyncStep()).isTrue();
    CaptureConfig copy = custom.toBuilder().asyncStep(false).build();
    assertThat(copy.trustedProxies()).containsExactly("10.0.0.0/8");
    assertThat(copy.asyncStep()).isFalse();
    assertThat(custom.withAsyncStep(true)).isSameAs(custom);

    AgentControl control = new AgentControl();
    control.setCapture(custom);
    assertThat(CaptureConfig.current()).isSameAs(custom);
    assertThat(NoopTracer.INSTANCE.capture()).isSameAs(custom);
    control.setCapture(null);
    assertThat(CaptureConfig.current()).isSameAs(CaptureConfig.DEFAULT);
  }

  private static TraceContext context(boolean virtual) {
    return new TraceContext(
        1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), virtual, -1, -1);
  }

  /** Every {@code public static final int} constant of {@code type}, in declaration order. */
  private static Map<String, Integer> constants(Class<?> type) throws IllegalAccessException {
    Map<String, Integer> values = new LinkedHashMap<String, Integer>();
    for (Field field : type.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (Modifier.isPublic(modifiers)
          && Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)
          && field.getType() == int.class) {
        values.put(field.getName(), field.getInt(null));
      }
    }
    return values;
  }

  private static Map<String, Integer> map(Object... namesAndValues) {
    Map<String, Integer> values = new LinkedHashMap<String, Integer>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      values.put((String) namesAndValues[i], (Integer) namesAndValues[i + 1]);
    }
    return values;
  }
}
