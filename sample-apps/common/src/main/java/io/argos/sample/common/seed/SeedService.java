/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.seed;

import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.http.PayService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Framework-neutral part of the seed endpoints {@code /seed/*} (18 §4.3). Each method plants the
 * seed where 18 §4.3 says (bind parameter, Statement literal, exception message, thread name,
 * outgoing call path) and returns a JSON-ready map that contains the seed, so every seed response
 * contains the sentinel. The web layers add the header and session parts.
 */
public final class SeedService {

  private final ShopJdbc jdbc;
  private final PayService pay;

  public SeedService(ShopJdbc jdbc, PayService pay) {
    this.jdbc = jdbc;
    this.pay = pay;
  }

  /**
   * GET /seed/email: query string value as bind parameter and Statement literal; {@code fail=true}
   * puts the email in an exception message instead.
   */
  public Map<String, Object> email(String email, String contactHeader, boolean fail) {
    if (fail) {
      throw new IllegalStateException("contact lookup failed for " + email);
    }
    Map<String, Object> result = jdbc.seedBindAndLiteral(email);
    result.put("contact", contactHeader);
    return result;
  }

  /** GET /seed/phone: bind parameters; {@code fail=true} puts the phone in an exception message. */
  public Map<String, Object> phone(String phone, boolean fail) {
    if (fail) {
      throw new IllegalStateException("payment declined for phone " + phone);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("seed", jdbc.seedBindAndLiteral(phone));
    result.put("pattern", jdbc.seedBindAndLiteral(Seeds.PHONE_PATTERN));
    return result;
  }

  /** GET /seed/rrn: resident number shapes as bind parameter and Statement literal. */
  public Map<String, Object> residentNumber() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("seed", jdbc.seedBindAndLiteral(Seeds.RESIDENT_NUMBER));
    result.put("pattern", jdbc.seedBindAndLiteral(Seeds.RESIDENT_NUMBER_PATTERN));
    return result;
  }

  /** GET /seed/card: card shapes as bind parameters; {@code fail=true}: exception message. */
  public Map<String, Object> card(boolean fail) {
    if (fail) {
      throw new IllegalStateException(
          "card " + Seeds.CARD + " declined (pattern " + Seeds.CARD_PATTERN + ")");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("seed", jdbc.seedBindAndLiteral(Seeds.CARD));
    result.put("pattern", jdbc.seedBindAndLiteral(Seeds.CARD_PATTERN));
    return result;
  }

  /** GET /seed/literals: Korean, doubled quote and dollar-quoted Statement literals. */
  public Map<String, Object> literals() {
    return jdbc.seedRawLiterals(Seeds.SQL_LITERALS);
  }

  /**
   * GET /seed/env: which of the secret-looking variables of {@link Seeds#environment()} the process
   * has and whether their values carry the sentinel. Never the values.
   */
  public Map<String, Object> environment(Map<String, String> processEnvironment) {
    Map<String, Object> variables = new LinkedHashMap<>();
    for (String name : Seeds.environment().keySet()) {
      String value = processEnvironment.get(name);
      variables.put(
          name, value == null ? "absent" : value.contains(Seeds.SENTINEL) ? "seeded" : "set");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("variables", variables);
    return result;
  }

  /** GET /seed/xss-sql?i=: payload as SQL literal and bind parameter. */
  public Map<String, Object> xssSql(int index) {
    return jdbc.seedBindAndLiteral(Seeds.pick(Seeds.XSS, index));
  }

  /**
   * GET /seed/xss-error?i=: the exception to throw (unhandled) with the payload in its message; the
   * web layer throws it: {@code throw seeds.xssError(i);}.
   */
  public IllegalStateException xssError(int index) {
    return new IllegalStateException("order lookup failed for " + Seeds.pick(Seeds.XSS, index));
  }

  /**
   * GET /seed/xss-thread?i=: the request thread carries the payload as its name during the request;
   * with SAMPLE_CUSTOM_EXECUTOR=true one task also runs on a {@code seed-ARGOS_SEED-N} thread.
   */
  public Map<String, Object> xssThread(int index, ExecutorService customExecutor)
      throws InterruptedException, ExecutionException, TimeoutException {
    String payload = Seeds.pick(Seeds.XSS, index);
    Thread current = Thread.currentThread();
    String original = current.getName();
    Map<String, Object> result = new LinkedHashMap<>();
    current.setName("seed-" + payload);
    try {
      result.put("threadName", current.getName());
      if (customExecutor != null) {
        Future<String> task = customExecutor.submit(() -> Thread.currentThread().getName());
        result.put("executorThread", task.get(10, TimeUnit.SECONDS));
      }
    } finally {
      current.setName(original);
    }
    return result;
  }

  /** GET /seed/xss-ext?i=: payload in the path of an ext-stub call (default client). */
  public PayService.Result xssExternal(int index) {
    return pay.callSeedPath(PayService.DEFAULT_CLIENT, Seeds.pick(Seeds.XSS, index));
  }

  /** GET /seed/csv/**: CSV formula (from the path) as SQL literal and bind parameter. */
  public Map<String, Object> csv(String payload) {
    Map<String, Object> result = jdbc.seedBindAndLiteral(payload);
    result.put("csv", payload);
    return result;
  }
}
