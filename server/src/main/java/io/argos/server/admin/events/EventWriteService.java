/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.events;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.collector.limits.SlidingWindowCounter;
import io.argos.server.storage.postgres.app.EventWriteRepository;
import io.argos.server.storage.postgres.app.EventWriteRepository.StoredEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deploy markers and custom events written through the API (14 §4.12, F-S18, ADR-0010 C-153, 02
 * C-12): {@code POST /events} by an operator session or an application's agent token, {@code DELETE
 * /events/{eventId}} by an administrator. The caller resolves the application and checks that the
 * writer may write it.
 *
 * <ul>
 *   <li>type {@code deploy} or {@code custom}; title 1 to 256 characters; version at most 128;
 *       detail at most 2 KB of UTF-8, stored as {@code {"text": detail}}; ts (default now) between
 *       400 days ago and 1 hour ahead;
 *   <li>at most {@value #PER_APP_PER_MINUTE} events per application per minute ({@code 429
 *       rate_limited} with {@code Retry-After}), counted in memory per server;
 *   <li>only {@code deploy} and {@code custom} events can be deleted ({@code 409 invalid_state} for
 *       the automatic ones).
 * </ul>
 */
@Service
public class EventWriteService {

  /** Events an application may receive per minute (14 §4.12). */
  public static final int PER_APP_PER_MINUTE = 60;

  /** Characters of a title (14 §4.12, {@code events.title VARCHAR(256)}). */
  public static final int MAX_TITLE_CHARS = 256;

  /** Characters of a version ({@code events.version VARCHAR(128)}). */
  public static final int MAX_VERSION_CHARS = 128;

  /** UTF-8 bytes of a detail (14 §4.12: 2 KB). */
  public static final int MAX_DETAIL_BYTES = 2 * 1024;

  /** The types a client may write and delete. */
  public static final Set<String> TYPES = Set.of("deploy", "custom");

  /** How far back a client-supplied {@code ts} may lie. */
  static final Duration MAX_PAST = Duration.ofDays(400);

  /** How far ahead a client-supplied {@code ts} may lie (clock differences). */
  static final Duration MAX_FUTURE = Duration.ofHours(1);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final EventWriteRepository events;
  private final AuditService audit;
  private final Clock clock;
  private final SlidingWindowCounter<Integer> perApp =
      new SlidingWindowCounter<>(
          PER_APP_PER_MINUTE, Duration.ofMinutes(1), 100_000, Duration.ofMinutes(2));

  @Autowired
  public EventWriteService(EventWriteRepository events, AuditService audit, Clock clock) {
    this.events = events;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * An event to write, as the client sent it (validated here).
   *
   * @param type {@code deploy} or {@code custom}
   * @param title the title
   * @param version the version, or null
   * @param detail the detail text, or null
   * @param ts ISO-8601 time, or null for now
   */
  public record NewEvent(
      String type,
      String title,
      @Nullable String version,
      @Nullable String detail,
      @Nullable String ts) {}

  /**
   * Writes an event of application {@code appId} ({@code env} its environment).
   *
   * @param createdBy the user who writes it, or null for an agent token
   * @param actor the audit actor (the user, or the token's prefix)
   * @return the event id
   * @throws ApiException 400 for an invalid field, 429 {@code rate_limited} beyond the per-app
   *     limit
   */
  public UUID create(
      int appId,
      String appName,
      String env,
      NewEvent event,
      @Nullable UUID createdBy,
      Actor actor) {
    String type = type(event.type());
    String title = text("title", event.title(), MAX_TITLE_CHARS, true);
    String version = text("version", event.version(), MAX_VERSION_CHARS, false);
    String detail = detail(event.detail());
    Instant now = clock.instant();
    Instant ts = event.ts() == null || event.ts().isBlank() ? now : ts(event.ts(), now);
    long nowMs = now.toEpochMilli();
    if (perApp.atLimit(appId, nowMs)) {
      throw new ApiException(ErrorCode.RATE_LIMITED, "At most 60 events per application a minute")
          .header("Retry-After", "60");
    }
    perApp.record(appId, nowMs);
    UUID id = UUID.randomUUID();
    events.insert(id, appId, env, type, title, detail, version, ts, createdBy);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("event_id", id.toString());
    after.put("type", type);
    after.put("env", env);
    after.put("title_chars", title.length());
    if (version != null) {
      after.put("version", version);
    }
    audit.record(
        AuditEvent.EVENT_CREATED,
        actor,
        new Target("app", Integer.toString(appId), appName),
        AuditResult.success(),
        null,
        after);
    return id;
  }

  /** The application and stored type of event {@code id}. */
  public Optional<StoredEvent> find(UUID id) {
    return events.find(id);
  }

  /**
   * Deletes a deploy marker or custom event (already resolved and allowed by the caller).
   *
   * @throws ApiException 409 {@code invalid_state} for an automatic event, 404 when it is gone
   */
  public void delete(UUID id, StoredEvent event, String appName, Actor actor) {
    if (!TYPES.contains(event.type())) {
      throw new ApiException(ErrorCode.INVALID_STATE, "Only deploy and custom events are deleted")
          .detail("event", "automatic_event");
    }
    if (!events.delete(id, event.type())) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    audit.record(
        AuditEvent.EVENT_DELETED,
        actor,
        new Target("event", id.toString(), null),
        AuditResult.success(),
        null,
        Map.of(
            "type",
            event.type(),
            "app_id",
            event.appId() == null ? "" : event.appId().toString(),
            "app",
            appName == null ? "" : appName));
  }

  private static String type(String value) {
    String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    if (!TYPES.contains(v)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "type is deploy or custom")
          .detail("type", v.isEmpty() ? "required" : "unsupported_value");
    }
    return v;
  }

  /** A single-line text: stripped, control characters refused. */
  private static String text(String field, String value, int maxChars, boolean required) {
    String v = value == null ? "" : value.strip();
    if (v.isEmpty()) {
      if (required) {
        throw new ApiException(ErrorCode.INVALID_REQUEST).detail(field, "required");
      }
      return null;
    }
    if (v.length() > maxChars) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail(field, "too_long");
    }
    for (int i = 0; i < v.length(); i++) {
      if (Character.isISOControl(v.charAt(i))) {
        throw new ApiException(ErrorCode.INVALID_REQUEST).detail(field, "control_character");
      }
    }
    return v;
  }

  /** The detail as stored JSON, {@code {"text": ...}}; null when absent. */
  private static String detail(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "detail is at most 2 KB")
          .detail("detail", "too_long");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
        throw new ApiException(ErrorCode.INVALID_REQUEST).detail("detail", "control_character");
      }
    }
    return JSON.writeValueAsString(Map.of("text", value));
  }

  private static Instant ts(String value, Instant now) {
    Instant ts = QueryLimits.time("ts", value);
    if (ts.isBefore(now.minus(MAX_PAST)) || ts.isAfter(now.plus(MAX_FUTURE))) {
      throw new ApiException(
              ErrorCode.INVALID_REQUEST, "ts is within 400 days ago and 1 hour ahead")
          .detail("ts", "out_of_range");
    }
    return ts;
  }
}
