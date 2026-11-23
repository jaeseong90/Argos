/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import io.argos.server.api.apps.AppScope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The events of an application (14 §4.12, GET; deploy marker creation and deletion are {@link
 * EventsWriteController}, WO-26, ADR-0010 C-153).
 */
@RestController
@RequiresRole(Role.VIEWER)
public class EventsController {

  private final EventService events;
  private final AppScope scope;

  public EventsController(EventService events, AppScope scope) {
    this.events = events;
    this.scope = scope;
  }

  /** The events of a range (14 §4.12). */
  @GetMapping("/api/v1/apps/{appId}/events")
  @Operation(
      operationId = "events_list",
      summary = "Events of an application",
      description =
          "Deploy markers and the automatic events (start, stop, config, state, apdex) of [from,"
              + " to), newest first, at most 1,000 (meta.truncated when there are more). type"
              + " filters by deploy, start, stop, config, state, apdex or custom. The automatic"
              + " state events are written by the instance state machine (WO-22).")
  @ArgosLimits(rangeMax = "1y", rowsMax = EventService.MAX_EVENTS)
  @ArgosSource({"postgres:events"})
  public ResponseEnvelope<List<EventViews.Event>> list(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @Parameter(description = "deploy, start, stop, config, state, apdex or custom")
          @RequestParam(value = "type", required = false)
          List<String> type) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.range(f, t, QueryLimits.SERIES_RANGE_MAX);
    List<String> types = new ArrayList<>();
    for (String value : QueryLimits.array("type", type, QueryLimits.MAX_ARRAY_VALUES)) {
      String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
      if (!EventViews.TYPES.contains(v)) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("type", "unsupported_value");
      }
      types.add(v);
    }
    int id = scope.app(appId).id();
    List<EventViews.Event> rows = events.events(id, f, t, types, EventService.MAX_EVENTS + 1);
    boolean truncated = rows.size() > EventService.MAX_EVENTS;
    return ResponseEnvelope.builder(rows.stream().limit(EventService.MAX_EVENTS).toList())
        .range(f, t)
        .truncated(truncated)
        .build();
  }
}
