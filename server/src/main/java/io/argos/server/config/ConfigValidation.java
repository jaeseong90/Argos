/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ConfigKeyCatalog.ConfigKey;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Start step {@code config-validation} ({@code CONFIG}): checks {@link ArgosProperties} against the
 * constraint annotations of its components and the rules between keys, and refuses the start with
 * one ERROR line that lists every violation as {@code <key>: <reason> (<source>)}. Values are not
 * printed (a value could be a secret); only an invalid address range is quoted.
 *
 * <p>Rules besides the annotations: {@code web.public-url} is an absolute http(s) URL without user
 * information; {@code clickhouse.url} is an http(s) URL; every duration is positive; retention
 * values are whole days; {@code collector.heartbeat-interval < stale-after < down-after}; {@code
 * wal.segment-bytes <= max-bytes}; {@code writer.flush-rows <= queue-rows}; {@code
 * auth.password.min-length <= max-length}; {@code auth.refresh-idle-ttl <= refresh-max-ttl}; {@code
 * ops.disk.warn-ratio < emergency-ratio < stop-ratio}; {@code ops.emergency.target-ratio <
 * ops.disk.emergency-ratio}; {@code ops.reconcile.cron}, {@code ops.dictionary-cleanup.cron} and
 * {@code ops.error-group-cleanup.cron} are Spring cron expressions; every entry of {@code
 * metrics.allow-cidrs} and {@code alert.webhook.allow-cidrs} is an IP range ({@link CidrRange});
 * SMTP {@code starttls: optional} needs {@code insecure: true}; {@code retention.audit} is at least
 * {@value #MIN_AUDIT_RETENTION_DAYS} days (ADR-0010 C-116, the floor that PostgreSQL also
 * enforces); {@code privacy.deletion.max-range} is whole days between 1 and 31.
 */
@Component
@Order(ConfigValidation.ORDER)
public class ConfigValidation implements StartupPhase {

  /** Position of this step in the {@code CONFIG} phase; the other config steps are relative. */
  public static final int ORDER = 1000;

  /** Shortest audit retention (ADR-0010 C-116; {@code audit_logs_maintain_partitions}, V014). */
  public static final int MIN_AUDIT_RETENTION_DAYS = 90;

  private final ArgosProperties properties;
  private final Validator validator;
  private final EffectiveServerConfig effective;

  @Autowired
  public ConfigValidation(ArgosProperties properties, EffectiveServerConfig effective) {
    this(properties, defaultValidator(), effective);
  }

  ConfigValidation(
      ArgosProperties properties, Validator validator, EffectiveServerConfig effective) {
    this.properties = properties;
    this.validator = validator;
    this.effective = effective;
  }

  /**
   * The validator of this step. Its messages are always English (log and error texts are English,
   * whatever the default locale of the host; the Spring validator would follow the host locale).
   */
  static Validator defaultValidator() {
    return EnglishValidator.FACTORY.getValidator();
  }

  /** Lives as long as the JVM; built on first use. */
  private static final class EnglishValidator {
    static final ValidatorFactory FACTORY = build();

    private static ValidatorFactory build() {
      Configuration<?> configuration = Validation.byDefaultProvider().configure();
      MessageInterpolator delegate = configuration.getDefaultMessageInterpolator();
      return configuration
          .messageInterpolator(new RootLocaleInterpolator(delegate))
          .buildValidatorFactory();
    }
  }

  /** Interpolates with the root locale: the base {@code ValidationMessages} bundle (English). */
  private record RootLocaleInterpolator(MessageInterpolator delegate)
      implements MessageInterpolator {

    @Override
    public String interpolate(String template, Context context) {
      return delegate.interpolate(template, context, Locale.ROOT);
    }

    @Override
    public String interpolate(String template, Context context, Locale locale) {
      return delegate.interpolate(template, context, Locale.ROOT);
    }
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "config-validation";
  }

  @Override
  public void run() throws StartupPhaseException {
    List<String> violations = violations();
    if (!violations.isEmpty()) {
      throw new StartupPhaseException("Invalid configuration: " + String.join("; ", violations));
    }
  }

  /** Every violation as {@code <key>: <reason> (<source>)}, in key order; empty when valid. */
  public List<String> violations() {
    List<Violation> found = new ArrayList<>();
    for (ConstraintViolation<ArgosProperties> v : validator.validate(properties)) {
      found.add(
          new Violation(ConfigKeyCatalog.keyOf(v.getPropertyPath().toString()), v.getMessage()));
    }
    crossChecks(found);
    Set<String> lines = new LinkedHashSet<>();
    found.stream()
        .sorted(Comparator.comparing(Violation::key))
        .forEach(v -> lines.add(v.key() + ": " + v.reason() + " (" + source(v.key()) + ")"));
    return List.copyOf(lines);
  }

  private String source(String key) {
    return effective == null ? EffectiveServerConfig.DEFAULT : effective.sourceOf(key);
  }

  private record Violation(String key, String reason) {}

  private void crossChecks(List<Violation> out) {
    ArgosProperties p = properties;
    publicUrl(p.web().publicUrl(), out);
    httpUrl("argos.clickhouse.url", p.clickhouse().url(), out);
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      if (key.valueIn(p) instanceof Duration d && (d.isNegative() || d.isZero())) {
        out.add(new Violation(key.name(), "must be a positive duration"));
      }
      if (key.name().startsWith("argos.retention.") && key.valueIn(p) instanceof Duration d) {
        if (d.toMillis() % Duration.ofDays(1).toMillis() != 0 || d.toDays() < 1) {
          out.add(new Violation(key.name(), "must be a whole number of days, at least 1d"));
        }
      }
    }
    Duration audit = p.retention().audit();
    if (audit != null && audit.compareTo(Duration.ofDays(MIN_AUDIT_RETENTION_DAYS)) < 0) {
      out.add(
          new Violation(
              "argos.retention.audit",
              "must be at least " + MIN_AUDIT_RETENTION_DAYS + "d (C-116, SEC-82)"));
    }
    Duration range = p.privacy().deletion().maxRange();
    if (range != null
        && (range.toMillis() % Duration.ofDays(1).toMillis() != 0
            || range.toDays() < 1
            || range.toDays() > 31)) {
      out.add(
          new Violation(
              "argos.privacy.deletion.max-range", "must be whole days between 1d and 31d"));
    }
    var c = p.collector();
    if (!(c.heartbeatInterval().compareTo(c.staleAfter()) < 0)) {
      out.add(
          new Violation("argos.collector.stale-after", "must be longer than heartbeat-interval"));
    }
    if (!(c.staleAfter().compareTo(c.downAfter()) < 0)) {
      out.add(new Violation("argos.collector.down-after", "must be longer than stale-after"));
    }
    if (p.wal().segmentBytes().toBytes() <= 0 || p.wal().maxBytes().toBytes() <= 0) {
      out.add(new Violation("argos.wal.max-bytes", "sizes must be positive"));
    } else if (p.wal().segmentBytes().compareTo(p.wal().maxBytes()) > 0) {
      out.add(new Violation("argos.wal.segment-bytes", "must not exceed max-bytes"));
    }
    if (p.writer().flushRows() > p.writer().queueRows()) {
      out.add(new Violation("argos.writer.flush-rows", "must not exceed queue-rows"));
    }
    if (p.auth().password().minLength() > p.auth().password().maxLength()) {
      out.add(new Violation("argos.auth.password.min-length", "must not exceed max-length"));
    }
    if (p.auth().refreshIdleTtl().compareTo(p.auth().refreshMaxTtl()) > 0) {
      out.add(new Violation("argos.auth.refresh-idle-ttl", "must not exceed refresh-max-ttl"));
    }
    var disk = p.ops().disk();
    if (!(disk.warnRatio() < disk.emergencyRatio() && disk.emergencyRatio() < disk.stopRatio())) {
      out.add(
          new Violation(
              "argos.ops.disk.emergency-ratio",
              "warn-ratio < emergency-ratio < stop-ratio is required"));
    }
    if (!(p.ops().emergency().targetRatio() < disk.emergencyRatio())) {
      out.add(
          new Violation(
              "argos.ops.emergency.target-ratio", "must be below argos.ops.disk.emergency-ratio"));
    }
    String cron = p.ops().reconcile().cron();
    if (cron != null && !cron.isBlank() && !CronExpression.isValidExpression(cron)) {
      out.add(
          new Violation(
              "argos.ops.reconcile.cron", "must be a cron expression of six fields (0 0 3 * * *)"));
    }
    String dictionaryCron = p.ops().dictionaryCleanup().cron();
    if (dictionaryCron != null
        && !dictionaryCron.isBlank()
        && !CronExpression.isValidExpression(dictionaryCron)) {
      out.add(
          new Violation(
              "argos.ops.dictionary-cleanup.cron",
              "must be a cron expression of six fields (0 30 3 * * *)"));
    }
    String errorGroupCron = p.ops().errorGroupCleanup().cron();
    if (errorGroupCron != null
        && !errorGroupCron.isBlank()
        && !CronExpression.isValidExpression(errorGroupCron)) {
      out.add(
          new Violation(
              "argos.ops.error-group-cleanup.cron",
              "must be a cron expression of six fields (0 45 3 * * *)"));
    }
    cidrs("argos.metrics.allow-cidrs", p.metrics().allowCidrs(), out);
    cidrs("argos.alert.webhook.allow-cidrs", p.alert().webhook().allowCidrs(), out);
    var smtp = p.alert().smtp();
    if (smtp.starttls() == ArgosProperties.Alert.Smtp.StartTls.OPTIONAL && !smtp.insecure()) {
      out.add(
          new Violation(
              "argos.alert.smtp.starttls",
              "optional needs argos.alert.smtp.insecure=true (SEC-54)"));
    }
  }

  private static void publicUrl(String value, List<Violation> out) {
    if (value == null) {
      return; // reported by @NotBlank
    }
    String key = "argos.web.public-url";
    URI uri = uri(value);
    if (uri == null || !uri.isAbsolute() || uri.getHost() == null || !isHttp(uri.getScheme())) {
      out.add(
          new Violation(
              key, "must be an absolute http or https URL, e.g. https://argos.example.com"));
    } else if (uri.getRawUserInfo() != null) {
      out.add(new Violation(key, "must not contain user information"));
    }
  }

  private static void httpUrl(String key, String value, List<Violation> out) {
    if (value == null) {
      return;
    }
    URI uri = uri(value);
    if (uri == null || !uri.isAbsolute() || uri.getHost() == null || !isHttp(uri.getScheme())) {
      out.add(new Violation(key, "must be an http or https URL"));
    }
  }

  private static boolean isHttp(String scheme) {
    String s = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    return s.equals("http") || s.equals("https");
  }

  private static URI uri(String value) {
    try {
      return new URI(value.strip());
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static void cidrs(String key, List<String> values, List<Violation> out) {
    for (int i = 0; i < values.size(); i++) {
      try {
        CidrRange.parse(values.get(i));
      } catch (IllegalArgumentException e) {
        out.add(
            new Violation(
                key + "[" + i + "]", "is not an IP address range (" + e.getMessage() + ")"));
      }
    }
  }
}
