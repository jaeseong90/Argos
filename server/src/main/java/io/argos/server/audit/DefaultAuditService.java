/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.config.ArgosProperties;
import io.argos.server.web.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit service (12 §8.1, 16 §11; WO-24, completed by WO-28).
 *
 * <p>Builds the {@link AuditRecord}: the time from the server's wall clock (UTC, microseconds;
 * every sink stores this value), values cut to the {@code audit_logs} column sizes, the client
 * address kept only when it is an IP literal, control characters of the user agent replaced, the
 * request id taken from the logging context of the request ({@link RequestIdFilter#MDC_KEY}),
 * {@code before}/{@code after} masked ({@link AuditMasking}) and written as JSON, the personal data
 * subject (SEC-81) normalised. Then every sink of {@code argos.audit.sinks} ({@link AuditSinks})
 * gets the record: the PostgreSQL sink first, then the syslog and JSON Lines sinks.
 *
 * <p>Failures (16 §11.3): a sink that throws is counted in {@value #FAILURES_METER} ({@code
 * argos_audit_write_failures_total}) and logged with the event name, request id and sink only; a
 * record the {@linkplain AuditSink#primary() primary} sink could not write is appended to the
 * fallback file ({@code argos.audit.fallback-file}). Recording never throws and never fails the
 * request.
 */
@Service
public class DefaultAuditService implements AuditService, DisposableBean {

  /** Micrometer name of the write-failure counter ({@code argos_audit_write_failures_total}). */
  public static final String FAILURES_METER = "argos.audit.write.failures";

  /** Characters of {@code audit_logs.subject_user_id}. */
  static final int SUBJECT_USER_ID_CHARS = 128;

  private static final Logger LOG = LoggerFactory.getLogger(DefaultAuditService.class);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** An IPv4 literal, or an IPv6 literal (hex groups, colons, optional IPv4 tail). */
  private static final Pattern IP_LITERAL =
      Pattern.compile(
          "\\d{1,3}(?:\\.\\d{1,3}){3}|(?=[0-9A-Fa-f:.]{2,45}$)[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");

  private final List<AuditSink> sinks;
  private final AuditSink fallback;
  private final Counter failures;
  private final Clock clock;

  /**
   * The server's service: the sinks of {@code argos.audit.sinks} over the {@link AuditSink} beans
   * (the PostgreSQL sink).
   *
   * @throws IllegalArgumentException for an invalid {@code argos.audit.*} configuration: the start
   *     is refused
   */
  @Autowired
  public DefaultAuditService(
      ObjectProvider<AuditSink> sinks,
      ObjectProvider<MeterRegistry> registry,
      ObjectProvider<ArgosProperties> properties) {
    this(
        configured(
            properties.getIfAvailable() == null ? null : properties.getIfAvailable().audit(),
            sinks.orderedStream().toList(),
            registry.getIfAvailable(SimpleMeterRegistry::new)),
        registry.getIfAvailable(SimpleMeterRegistry::new));
  }

  /** A service with explicit sinks and no fallback file (tests). */
  public DefaultAuditService(List<AuditSink> sinks, MeterRegistry registry) {
    this(sinks, null, registry, Clock.systemUTC());
  }

  /** A service over configured sinks ({@link AuditSinks#configure}; the CLI uses it too). */
  public DefaultAuditService(AuditSinks.Configured configured, MeterRegistry registry) {
    this(configured.sinks(), configured.fallback(), registry, Clock.systemUTC());
  }

  /**
   * A service with explicit sinks, fallback and clock (tests).
   *
   * @param fallback where records the primary sink could not write go, or null for none
   */
  public DefaultAuditService(
      List<AuditSink> sinks, AuditSink fallback, MeterRegistry registry, Clock clock) {
    this.sinks = List.copyOf(sinks);
    this.fallback = fallback;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.failures =
        Counter.builder(FAILURES_METER)
            .description("Audit records that could not be written to a sink")
            .register(registry);
  }

  /**
   * The configured sinks of {@code audit} (defaults when null) over {@code primary}.
   *
   * @throws IllegalArgumentException for an invalid configuration
   */
  public static AuditSinks.Configured configured(
      ArgosProperties.Audit audit, List<AuditSink> primary, MeterRegistry registry) {
    ArgosProperties.Audit a =
        audit == null ? new ArgosProperties.Audit(null, null, null, 1) : audit;
    return AuditSinks.configure(a, primary, registry);
  }

  /** The sinks, primary first (tests). */
  List<AuditSink> sinks() {
    return sinks;
  }

  @Override
  public void record(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after) {
    record(event, actor, target, result, before, after, null);
  }

  @Override
  public void record(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after,
      AuditSubject subject) {
    AuditRecord record;
    try {
      record =
          toRecord(
              now(),
              event,
              actor,
              target,
              result,
              before,
              after,
              subject,
              MDC.get(RequestIdFilter.MDC_KEY));
    } catch (RuntimeException e) {
      failures.increment();
      LOG.warn(
          "Audit event {} could not be prepared ({}); the request continues",
          event,
          e.getClass().getSimpleName());
      return;
    }
    write(record);
  }

  /** Writes a prepared record to every sink (the async dispatcher's worker uses it too). */
  void write(AuditRecord record) {
    for (AuditSink sink : sinks) {
      try {
        sink.write(record);
      } catch (RuntimeException e) {
        failures.increment();
        LOG.warn(
            "Audit event {} of request {} could not be written to {} ({}); the request continues",
            record.action(),
            record.requestId(),
            sink.getClass().getSimpleName(),
            e.getClass().getSimpleName());
        LOG.debug("Audit write failure", e);
        if (sink.primary()) {
          toFallback(record);
        }
      }
    }
  }

  private void toFallback(AuditRecord record) {
    if (fallback == null) {
      return;
    }
    try {
      fallback.write(record);
    } catch (RuntimeException e) {
      failures.increment();
      LOG.error(
          "Audit event {} of request {} could not be written to the fallback file either ({})",
          record.action(),
          record.requestId(),
          e.getClass().getSimpleName());
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public void destroy() {
    List<AuditSink> all = new ArrayList<>(sinks);
    if (fallback != null) {
      all.add(fallback);
    }
    for (AuditSink sink : all) {
      try {
        sink.close();
      } catch (RuntimeException e) {
        LOG.debug("Audit sink close failed", e);
      }
    }
  }

  /** The row of one event without a subject (tests). */
  static AuditRecord toRecord(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after,
      String requestId) {
    return toRecord(
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        event,
        actor,
        target,
        result,
        before,
        after,
        null,
        requestId);
  }

  /** The row of one event (package-private for tests and the async dispatcher). */
  static AuditRecord toRecord(
      Instant ts,
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after,
      AuditSubject subject,
      String requestId) {
    Actor a = actor == null ? Actor.system() : actor;
    Target t = target == null ? Target.none() : target;
    AuditResult r = result == null ? AuditResult.success() : result;
    return new AuditRecord(
        ts,
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
        json(after),
        subject == null ? null : subjectUserId(subject.userId()),
        subject == null ? null : subject.txId());
  }

  private static String json(Map<String, ?> values) {
    Map<String, Object> masked = AuditMasking.mask(values);
    return masked == null ? null : JSON.writeValueAsString(masked);
  }

  /** A user id without control characters, trimmed, at most 128 characters; null when empty. */
  static String subjectUserId(String userId) {
    if (userId == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(Math.min(userId.length(), SUBJECT_USER_ID_CHARS));
    userId.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(out::appendCodePoint);
    String v = cut(out.toString().strip(), SUBJECT_USER_ID_CHARS);
    return v == null || v.isEmpty() ? null : v;
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
