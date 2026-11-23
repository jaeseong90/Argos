/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditService;
import io.argos.server.storage.postgres.app.EventWriteRepository;
import io.argos.server.storage.postgres.app.EventWriteRepository.StoredEvent;
import io.argos.server.testing.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The write rules of deploy markers and custom events (14 §4.12, ADR-0010 C-153). */
@Tag("req:F-S18")
@Tag("req:C-153")
class EventWriteServiceTest {

  private Instant now;
  private static final Actor ACTOR =
      new Actor(Actor.Type.AGENT, "argos_abcdefgh", null, null, null);

  private EventWriteRepository repository;
  private AuditService audit;
  private MutableClock clock;
  private EventWriteService service;

  @BeforeEach
  void setUp() {
    repository = mock(EventWriteRepository.class);
    audit = mock(AuditService.class);
    clock = new MutableClock();
    now = clock.instant();
    service = new EventWriteService(repository, audit, clock);
  }

  private static EventWriteService.NewEvent deploy(String title) {
    return new EventWriteService.NewEvent("deploy", title, "1.4.2", "release notes", null);
  }

  private static String refusal(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      return e.errorCode().code()
          + (e.details().isEmpty()
              ? ""
              : "/" + e.details().get(0).field() + "/" + e.details().get(0).reason());
    }
    throw new AssertionError("no refusal");
  }

  @Test
  @DisplayName("a deploy marker is stored with ts now, the detail as {\"text\": ...}, and audited")
  void storesAndAudits() {
    UUID id = service.create(7, "shop", "prod", deploy(" Release 1.4.2 "), null, ACTOR);
    verify(repository)
        .insert(
            eq(id),
            eq(7),
            eq("prod"),
            eq("deploy"),
            eq("Release 1.4.2"),
            eq("{\"text\":\"release notes\"}"),
            eq("1.4.2"),
            eq(now),
            isNull());
    verify(audit).record(eq(AuditEvent.EVENT_CREATED), eq(ACTOR), any(), any(), isNull(), any());
  }

  @Test
  @DisplayName("type, title, version, detail and ts are validated before anything is stored")
  void validation() {
    assertThat(
            refusal(
                () ->
                    service.create(
                        1,
                        "a",
                        "e",
                        new EventWriteService.NewEvent("start", "t", null, null, null),
                        null,
                        ACTOR)))
        .isEqualTo("invalid_request/type/unsupported_value");
    assertThat(refusal(() -> service.create(1, "a", "e", deploy(" "), null, ACTOR)))
        .isEqualTo("invalid_request/title/required");
    assertThat(refusal(() -> service.create(1, "a", "e", deploy("t".repeat(257)), null, ACTOR)))
        .isEqualTo("invalid_request/title/too_long");
    assertThat(
            refusal(
                () ->
                    service.create(
                        1,
                        "a",
                        "e",
                        new EventWriteService.NewEvent("custom", "t", null, "d".repeat(2049), null),
                        null,
                        ACTOR)))
        .isEqualTo("payload_too_large/detail/too_long");
    assertThat(
            refusal(
                () ->
                    service.create(
                        1,
                        "a",
                        "e",
                        new EventWriteService.NewEvent(
                            "custom", "t", null, null, now.plus(Duration.ofHours(2)).toString()),
                        null,
                        ACTOR)))
        .isEqualTo("invalid_request/ts/out_of_range");
    verify(repository, never())
        .insert(any(), anyInt(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("60 events per application a minute; the 61st is 429 with Retry-After, others pass")
  void perAppRateLimit() {
    for (int i = 0; i < EventWriteService.PER_APP_PER_MINUTE; i++) {
      service.create(3, "a", "e", deploy("r" + i), null, ACTOR);
    }
    ApiException limited = null;
    try {
      service.create(3, "a", "e", deploy("one more"), null, ACTOR);
    } catch (ApiException e) {
      limited = e;
    }
    assertThat(limited).isNotNull();
    assertThat(limited.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(limited.headers()).containsKey("Retry-After");
    service.create(4, "b", "e", deploy("other app"), null, ACTOR);
    clock.advance(Duration.ofSeconds(61));
    service.create(3, "a", "e", deploy("after a minute"), null, ACTOR);
    verify(repository, times(EventWriteService.PER_APP_PER_MINUTE + 2))
        .insert(any(), anyInt(), anyString(), anyString(), anyString(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("only deploy and custom events are deleted; automatic ones are 409 invalid_state")
  void deleteRules() {
    UUID id = UUID.randomUUID();
    when(repository.delete(id, "deploy")).thenReturn(true);
    service.delete(id, new StoredEvent(1, "deploy"), "shop", ACTOR);
    verify(audit).record(eq(AuditEvent.EVENT_DELETED), eq(ACTOR), any(), any(), isNull(), any());
    assertThat(refusal(() -> service.delete(id, new StoredEvent(1, "state"), "shop", ACTOR)))
        .isEqualTo("invalid_state/event/automatic_event");
    when(repository.delete(id, "custom")).thenReturn(false);
    assertThat(refusal(() -> service.delete(id, new StoredEvent(1, "custom"), "shop", ACTOR)))
        .isEqualTo("not_found");
  }
}
