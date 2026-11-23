/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.admin.events.EventWriteService;
import io.argos.server.api.Envelope;
import io.argos.server.api.apps.AppScope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStoreUnavailableException;
import io.argos.server.session.AppRef;
import io.argos.server.storage.postgres.app.AppRepository;
import io.argos.server.storage.postgres.app.EventWriteRepository.StoredEvent;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deploy markers and custom events written through the API (14 §4.12, ADR-0010 C-153, 02 C-12):
 * {@code POST /api/v1/events} and {@code DELETE /api/v1/events/{eventId}}.
 *
 * <p>{@code POST /events} is the one endpoint with a second way to authenticate (14 §2.2): an
 * operator's session, or {@code Authorization: Bearer argos_...} with an application's agent token
 * (deploy pipelines). A request with an {@code Authorization} header is handled by {@link
 * #createWithToken}, a separate handler ({@code PUBLIC} for the role interceptor because no session
 * stands behind it) that authenticates the token itself before anything else: an unknown,
 * malformed, expired or revoked token is 401 {@code unauthenticated}, a token writes only its home
 * application (403 otherwise, ADR-0010 C-155), and the audit actor is the token's prefix. CSRF is
 * skipped for exactly this case ({@code CsrfFilter}): a browser cannot send the header across
 * origins without a CORS preflight, which the server refuses. That handler is hidden from the
 * document; the documented operation describes both ways.
 */
@RestController
public class EventsWriteController {

  /** The scheme prefix of the token header. */
  static final String BEARER = "Bearer ";

  private final EventWriteService events;
  private final AppScope scope;
  private final AppRepository appsByName;
  private final CatalogRepository catalog;
  private final TokenLookup tokens;
  private final Clock clock;

  public EventsWriteController(
      EventWriteService events,
      AppScope scope,
      AppRepository appsByName,
      CatalogRepository catalog,
      TokenLookup tokens,
      Clock clock) {
    this.events = events;
    this.scope = scope;
    this.appsByName = appsByName;
    this.catalog = catalog;
    this.tokens = tokens;
    this.clock = clock;
  }

  /**
   * {@code POST /events}.
   *
   * @param appId the application by id, or null
   * @param app the application by name (with {@code env}), or null
   * @param env its environment
   * @param type {@code deploy} or {@code custom}
   * @param title title, 1 to 256 characters
   * @param version version, at most 128 characters, or null
   * @param detail detail text, at most 2 KB, or null
   * @param ts ISO-8601 time, default now
   */
  @Schema(name = "EventCreateRequest")
  public record CreateRequest(
      @JsonProperty("app_id") @Nullable Integer appId,
      @Nullable String app,
      @Nullable String env,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "deploy or custom") String type,
      @Schema(requiredMode = RequiredMode.REQUIRED) String title,
      @Nullable String version,
      @Nullable String detail,
      @Nullable String ts) {}

  /**
   * The answer of {@code POST /events}.
   *
   * @param eventId the new event
   */
  @Schema(name = "EventCreated")
  public record Created(@JsonProperty("event_id") String eventId) {}

  /** Writes an event with an operator's session (14 §4.12). */
  @PostMapping("/api/v1/events")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.EVENT_CREATED)
  @Operation(
      operationId = "events_create",
      summary = "Create a deploy marker or custom event",
      description =
          "The application is app_id, or app (name) with env. type deploy or custom, title 1 to"
              + " 256 characters, version at most 128, detail at most 2 KB (stored as"
              + " {\"text\": detail}), ts default now (400 days ago to 1 hour ahead). At most 60"
              + " events per application a minute (429 rate_limited with Retry-After). Instead of"
              + " a session, a deploy pipeline may send 'Authorization: Bearer argos_...' with an"
              + " agent token of the application (no CSRF header then): the token writes only its"
              + " home application (403 otherwise, the home application when app_id and app are"
              + " absent), an invalid, expired or revoked token is 401, and the audit records the"
              + " token's prefix.")
  public Envelope<Created> create(
      @RequestBody(required = false) CreateRequest body, HttpServletRequest request) {
    CreateRequest b = required(body);
    ArgosPrincipal p = principal();
    AppRecord app = resolve(b);
    UUID id =
        events.create(
            app.id(), app.name(), app.env(), event(b), p.userId(), Actor.user(p, request));
    return Envelope.of(new Created(id.toString()));
  }

  /**
   * Writes an event with an application's agent token (14 §2.2, 02 C-12); see the class comment.
   */
  @PostMapping(value = "/api/v1/events", headers = HttpHeaders.AUTHORIZATION)
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRole(Role.PUBLIC)
  @Hidden
  public Envelope<Created> createWithToken(
      @Sensitive @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
      @RequestBody(required = false) CreateRequest body,
      HttpServletRequest request) {
    TokenRecord token = authenticate(authorization);
    AppRecord home =
        catalog.app(token.appId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    CreateRequest b = required(body);
    if (b.appId() != null && b.appId() != home.id()) {
      throw new ApiException(ErrorCode.FORBIDDEN, "The token writes its own application only")
          .detail("app_id", "other_app");
    }
    if ((b.app() != null && !b.app().strip().equals(home.name()))
        || (b.env() != null && !b.env().strip().equals(home.env()))) {
      throw new ApiException(ErrorCode.FORBIDDEN, "The token writes its own application only")
          .detail("app", "other_app");
    }
    Actor actor =
        new Actor(
            Actor.Type.AGENT,
            token.prefix(),
            token.prefix(),
            request.getRemoteAddr(),
            request.getHeader(HttpHeaders.USER_AGENT));
    UUID id = events.create(home.id(), home.name(), home.env(), event(b), null, actor);
    return Envelope.of(new Created(id.toString()));
  }

  /** Deletes a deploy marker or custom event (14 §4.12). */
  @DeleteMapping("/api/v1/events/{eventId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.ADMIN)
  @ArgosAudit(AuditEvent.EVENT_DELETED)
  @Operation(
      operationId = "events_delete",
      summary = "Delete a deploy marker or custom event",
      description =
          "Only deploy and custom events are deleted (409 invalid_state for the automatic ones);"
              + " 404 for an unknown event.")
  public void delete(@PathVariable("eventId") String eventId, HttpServletRequest request) {
    UUID id = ObjectAccessPolicy.requireUuidV4(eventId, "eventId");
    StoredEvent stored = events.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    if (stored.appId() == null) {
      throw new ApiException(ErrorCode.INVALID_STATE, "Only deploy and custom events are deleted")
          .detail("event", "automatic_event");
    }
    AppRecord app = scope.app(stored.appId());
    events.delete(id, stored, app.name(), Actor.user(principal(), request));
  }

  /** The token of {@code Authorization: Bearer <token>}, if it authenticates now. */
  private TokenRecord authenticate(@Sensitive String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER)) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
    String token = authorization.substring(BEARER.length()).strip();
    if (!TokenHash.isWellFormed(token)) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
    Optional<TokenRecord> record;
    try {
      record = tokens.find(TokenHash.of(token));
    } catch (TokenStoreUnavailableException e) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    if (record.isEmpty() || !record.get().verdict(clock.instant()).accepted()) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
    return record.get();
  }

  /** The application of a session request: {@code app_id}, or {@code app} with {@code env}. */
  private AppRecord resolve(CreateRequest b) {
    if (b.appId() != null) {
      return scope.app(b.appId());
    }
    if (b.app() == null || b.app().isBlank() || b.env() == null || b.env().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "app_id, or app with env, is required")
          .detail("app_id", "required");
    }
    AppRef ref =
        appsByName
            .find(b.app().strip(), b.env().strip())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return scope.app(ref.id());
  }

  private static CreateRequest required(CreateRequest body) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("type", "required");
    }
    return body;
  }

  private static EventWriteService.NewEvent event(CreateRequest b) {
    return new EventWriteService.NewEvent(b.type(), b.title(), b.version(), b.detail(), b.ts());
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
