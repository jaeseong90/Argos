/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.web.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit service of WO-24 (12 §8.1, 16 §11; minimal: the PostgreSQL sink only, WO-28 adds the
 * other sinks, the fallback file and retention).
 *
 * <p>Builds the {@link AuditRecord}: values cut to the {@code audit_logs} column sizes, the client
 * address kept only when it is an IP literal, control characters of the user agent replaced, the
 * request id taken from the logging context of the request ({@link RequestIdFilter#MDC_KEY}), and
 * {@code before}/{@code after} masked ({@link AuditMasking}) and written as JSON. Then every {@link
 * AuditSink} gets the record; a sink that throws is counted in {@value #FAILURES_METER} ({@code
 * argos_audit_write_failures_total}) and logged with the event name and request id only. Recording
 * never throws (16 §11.3).
 */
@Service
public class DefaultAuditService implements AuditService {

  /** Micrometer name of the write-failure counter ({@code argos_audit_write_failures_total}). */
  public static final String FAILURES_METER = "argos.audit.write.failures";

  private static final Logger LOG = LoggerFactory.getLogger(DefaultAuditService.class);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** An IPv4 literal, or an IPv6 literal (hex groups, colons, optional IPv4 tail). */
  private static final Pattern IP_LITERAL =
      Pattern.compile(
          "\\d{1,3}(?:\\.\\d{1,3}){3}|(?=[0-9A-Fa-f:.]{2,45}$)[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");

  private final List<AuditSink> sinks;
  private final Counter failures;

  @Autowired
  public DefaultAuditService(
      ObjectProvider<AuditSink> sinks, ObjectProvider<MeterRegistry> registry) {
    this(sinks.orderedStream().toList(), registry.getIfAvailable(SimpleMeterRegistry::new));
  }

  /** A service with explicit sinks (tests). */
  public DefaultAuditService(List<AuditSink> sinks, MeterRegistry registry) {
    this.sinks = List.copyOf(sinks);
    this.failures =
        Counter.builder(FAILURES_METER)
            .description("Audit records that could not be written to a sink")
            .register(registry);
  }

  @Override
  public void record(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after) {
    AuditRecord record;
    try {
      record =
          toRecord(event, actor, target, result, before, after, MDC.get(RequestIdFilter.MDC_KEY));
    } catch (RuntimeException e) {
      failures.increment();
      LOG.warn(
          "Audit event {} could not be prepared ({}); the request continues",
          event,
          e.getClass().getSimpleName());
      return;
    }
    for (AuditSink sink : sinks) {
      try {
        sink.write(record);
      } catch (RuntimeException e) {
        failures.increment();
        LOG.warn(
            "Audit event {} of request {} could not be written to {} ({}); the request continues",
            event,
            record.requestId(),
            sink.getClass().getSimpleName(),
            e.getClass().getSimpleName());
        LOG.debug("Audit write failure", e);
      }
    }
  }

  /** The row of one event (package-private for tests). */
  static AuditRecord toRecord(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after,
      String requestId) {
    Actor a = actor == null ? Actor.system() : actor;
    Target t = target == null ? Target.none() : target;
    AuditResult r = result == null ? AuditResult.success() : result;
    return new AuditRecord(
        a.type().value(),
        cut(a.id(), 64),
        cut(a.name(), 128),
        ipLiteral(a.ip()),
        cut(printable(a.userAgent()), 256),
        cut(requestId, 64),
        event.name(),
        cut(t.type(), 32),
        cut(t.id(), 64),
        cut(t.name(), 256),
        r.outcome().value(),
        cut(r.reason(), 512),
        json(before),
        json(after));
  }

  private static String json(Map<String, ?> values) {
    Map<String, Object> masked = AuditMasking.mask(values);
    return masked == null ? null : JSON.writeValueAsString(masked);
  }

  /** The address without an IPv6 zone, when it is an IP literal; otherwise null. */
  static String ipLiteral(String ip) {
    if (ip == null) {
      return null;
    }
    int zone = ip.indexOf('%');
    String bare = zone >= 0 ? ip.substring(0, zone) : ip;
    if (bare.startsWith("[") && bare.endsWith("]")) {
      bare = bare.substring(1, bare.length() - 1);
    }
    return IP_LITERAL.matcher(bare).matches() ? bare : null;
  }

  /** Control characters (log and terminal injection) become U+FFFD. */
  static String printable(String text) {
    if (text == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? 0xFFFD : cp));
    return out.toString();
  }

  /** At most {@code max} characters (code points kept whole). */
  static String cut(String text, int max) {
    if (text == null || text.length() <= max) {
      return text;
    }
    int end = max;
    if (Character.isLowSurrogate(text.charAt(end))
        && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end);
  }
}
