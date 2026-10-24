/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.boot4.config.SampleExecutors;
import io.argos.sample.common.http.PayService;
import io.argos.sample.common.seed.SeedService;
import io.argos.sample.common.seed.Seeds;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PII, secret and XSS seed endpoints {@code /seed/*} (18 §4.3, DSN-18-09). Every answer contains
 * the seed it planted, so it contains the ARGOS_SEED sentinel. The database, exception and thread
 * parts live in common (SeedService); headers and the session are handled here.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

  private final SeedService seeds;
  private final SampleExecutors executors;

  public SeedController(SeedService seeds, SampleExecutors executors) {
    this.seeds = seeds;
    this.executors = executors;
  }

  @GetMapping("/email")
  public Map<String, Object> email(
      @RequestParam(name = "email") String email,
      @RequestHeader(name = "X-Contact", required = false) String contact,
      @RequestParam(name = "fail", defaultValue = "false") boolean fail) {
    return seeds.email(email, contact, fail);
  }

  @GetMapping("/phone")
  public Map<String, Object> phone(
      @RequestParam(name = "phone") String phone,
      @RequestParam(name = "fail", defaultValue = "false") boolean fail) {
    return seeds.phone(phone, fail);
  }

  @GetMapping("/rrn")
  public Map<String, Object> residentNumber() {
    return seeds.residentNumber();
  }

  @GetMapping("/card")
  public Map<String, Object> card(
      @RequestParam(name = "fail", defaultValue = "false") boolean fail) {
    return seeds.card(fail);
  }

  @GetMapping("/user")
  public Map<String, Object> user(
      @RequestHeader(name = "X-User-Id", required = false) String userId,
      HttpServletRequest request) {
    request.getSession(true).setAttribute("userId", userId);
    return Map.of("userId", String.valueOf(userId));
  }

  @GetMapping("/xff")
  public Map<String, Object> forwardedFor(
      @RequestHeader(name = "X-Forwarded-For", required = false) String forwardedFor,
      HttpServletRequest request) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("forwardedFor", forwardedFor);
    result.put("remoteAddr", request.getRemoteAddr());
    return result;
  }

  @GetMapping("/token")
  public Map<String, Object> token(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "X-Api-Key", required = false) String apiKey) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("authorization", authorization);
    result.put("apiKey", apiKey);
    return result;
  }

  @GetMapping("/literals")
  public Map<String, Object> literals() {
    return seeds.literals();
  }

  @GetMapping("/env")
  public Map<String, Object> environment() {
    return seeds.environment(System.getenv());
  }

  /** The XSS payload is the rest of the path (several segments: '/' stays unencoded). */
  @GetMapping("/xss/**")
  public Map<String, Object> xssPath(HttpServletRequest request) {
    return Map.of("payload", pathRemainder(request, "/seed/xss/"));
  }

  @GetMapping("/xss-query")
  public Map<String, Object> xssQuery(@RequestParam(name = "q") String q) {
    return Map.of("q", q);
  }

  @GetMapping("/xss-header")
  public Map<String, Object> xssHeader(@RequestHeader(name = "X-Seed") String seed) {
    return Map.of("seed", seed);
  }

  @GetMapping("/xss-user")
  public Map<String, Object> xssUser(
      @RequestHeader(name = "X-User-Id") String userId, HttpServletRequest request) {
    request.getSession(true).setAttribute("userId", userId);
    return Map.of("userId", userId);
  }

  @GetMapping("/xss-sql")
  public Map<String, Object> xssSql(@RequestParam(name = "i") int index) {
    return seeds.xssSql(index);
  }

  @GetMapping("/xss-error")
  public Map<String, Object> xssError(@RequestParam(name = "i") int index) {
    throw seeds.xssError(index);
  }

  @GetMapping("/xss-thread")
  public Map<String, Object> xssThread(@RequestParam(name = "i") int index)
      throws InterruptedException, ExecutionException, TimeoutException {
    return seeds.xssThread(index, executors.custom());
  }

  @GetMapping("/xss-ext")
  public ResponseEntity<Map<String, Object>> xssExternal(@RequestParam(name = "i") int index) {
    PayService.Result result = seeds.xssExternal(index);
    return ResponseEntity.status(result.httpStatus()).body(result.body());
  }

  /** CSV formula: the rest of the path; also stored as the session user id. */
  @GetMapping("/csv/**")
  public Map<String, Object> csv(HttpServletRequest request) {
    String payload = pathRemainder(request, "/seed/csv/");
    request.getSession(true).setAttribute("userId", payload);
    Map<String, Object> result = seeds.csv(payload);
    result.put("sentinel", payload.contains(Seeds.SENTINEL));
    return result;
  }

  /** Decoded request path after {@code prefix} (context path removed, '+' kept literally). */
  static String pathRemainder(HttpServletRequest request, String prefix) {
    String uri = request.getRequestURI().substring(request.getContextPath().length());
    String raw = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
    return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
  }
}
