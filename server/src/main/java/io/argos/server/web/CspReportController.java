/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.web.security.IpRateLimitFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives Content-Security-Policy violation reports (16 §9.9, SEC-46): public, as browsers send
 * reports without cookies or CSRF tokens; limited to {@value #MAX_BODY_BYTES} bytes per report and
 * 60 reports per minute per address ({@link IpRateLimitFilter}).
 *
 * <p>A report is untrusted text from any client. It is never stored and never echoed: it increments
 * {@code argos_csp_violations_total} and logs one line with the directive, the origin of the
 * blocked resource (paths and query strings can hold secrets) and the disposition, each cut to a
 * bounded length with control characters replaced. Both report formats are understood: {@code
 * application/csp-report} from {@code report-uri} and {@code application/reports+json} from {@code
 * report-to}. Answers 204; 400 {@code payload_too_large} over the limit, 400 {@code
 * invalid_request} for anything that is not a report.
 */
@RestController
public class CspReportController {

  /** The largest accepted report (16 §9.9: 8 KiB). */
  public static final int MAX_BODY_BYTES = 8 * 1024;

  /** Micrometer name of the counter ({@code argos_csp_violations_total}). */
  public static final String VIOLATIONS_METER = "argos.csp.violations";

  /** Longest logged field. */
  private static final int MAX_FIELD_LENGTH = 120;

  private static final Logger LOG = LoggerFactory.getLogger(CspReportController.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Counter violations;

  @Autowired
  public CspReportController(ObjectProvider<MeterRegistry> registry) {
    this(registry.getIfAvailable(SimpleMeterRegistry::new));
  }

  CspReportController(MeterRegistry registry) {
    this.violations =
        Counter.builder(VIOLATIONS_METER)
            .description("Content-Security-Policy violation reports received")
            .register(registry);
  }

  /** Takes one report. */
  @PostMapping(IpRateLimitFilter.CSP_REPORT_PATH)
  @RequiresRole(Role.PUBLIC)
  @ArgosAudit(none = "A report is counted and logged, never stored (16 §9.9)")
  public ResponseEntity<Void> report(HttpServletRequest request) throws IOException {
    JsonNode violation = violation(parse(read(request)));
    if (violation == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The body is not a CSP report");
    }
    violations.increment();
    LOG.warn(
        "CSP violation: directive={}, blocked={}, disposition={}",
        directive(violation),
        blocked(violation),
        clean(first(violation, "disposition")));
    return ResponseEntity.noContent().build();
  }

  private static byte[] read(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw tooLarge();
    }
    try (InputStream body = request.getInputStream()) {
      byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw tooLarge();
      }
      return bytes;
    }
  }

  private static ApiException tooLarge() {
    return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The report exceeds the limit of 8 KB");
  }

  private static JsonNode parse(byte[] body) {
    try {
      return JSON.readTree(body);
    } catch (JacksonException notJson) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The body is not a CSP report");
    }
  }

  /**
   * The violation object of a report: {@code {"csp-report": {...}}} of {@code report-uri}, or
   * {@code [{"type": "csp-violation", "body": {...}}]} of {@code report-to}; null for anything
   * else.
   */
  static JsonNode violation(JsonNode report) {
    if (report == null) {
      return null;
    }
    if (report.isObject() && report.path("csp-report").isObject()) {
      return report.path("csp-report");
    }
    if (report.isArray() && !report.isEmpty()) {
      JsonNode first = report.get(0);
      if ("csp-violation".equals(first.path("type").asString(""))
          && first.path("body").isObject()) {
        return first.path("body");
      }
    }
    return null;
  }

  private static String directive(JsonNode violation) {
    return clean(
        first(
            violation,
            "effective-directive",
            "effectiveDirective",
            "violated-directive",
            "violatedDirective"));
  }

  private static String blocked(JsonNode violation) {
    return clean(origin(first(violation, "blocked-uri", "blockedURL")));
  }

  private static String first(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.path(name);
      if (value.isString() && !value.asString().isBlank()) {
        return value.asString();
      }
    }
    return "";
  }

  /**
   * {@code scheme://host[:port]} of a URL. A keyword such as {@code inline}, {@code eval} or {@code
   * data} stays as it is; a value that cannot be read as a URL becomes {@code other}.
   */
  static String origin(String blocked) {
    if (blocked.isEmpty() || blocked.indexOf(':') < 0) {
      return blocked.length() <= 16 && blocked.chars().allMatch(Character::isLetter)
          ? blocked
          : "other";
    }
    try {
      URI uri = new URI(blocked);
      if (uri.getHost() == null) {
        return uri.getScheme() == null ? "other" : uri.getScheme();
      }
      return uri.getScheme()
          + "://"
          + uri.getHost()
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    } catch (URISyntaxException notUrl) {
      return "other";
    }
  }

  /** Control characters replaced (a report cannot forge log lines), length bounded. */
  static String clean(String value) {
    String bounded =
        value.length() <= MAX_FIELD_LENGTH ? value : value.substring(0, MAX_FIELD_LENGTH);
    StringBuilder out = new StringBuilder(bounded.length());
    bounded.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? '?' : c));
    return out.toString();
  }
}
