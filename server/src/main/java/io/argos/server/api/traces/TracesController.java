/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.traces;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.api.query.Source;
import io.argos.server.api.transactions.Ids;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A distributed trace (14 §4.7, F-U03). A trace id names no application ({@code app} is not needed,
 * DSN-14-07). {@code window=30d} is a heavy request (14 §2.7, SEC-56): it takes a token of the
 * user's heavy bucket here, since the other windows must not.
 */
@RestController
@RequiresRole(Role.VIEWER)
public class TracesController {

  /** The window that is a heavy request (14 §4.7). */
  static final String HEAVY_WINDOW = "30d";

  private final TraceService traces;
  private final AppAccessPolicy access;
  private final RateLimiter limiter;

  public TracesController(TraceService traces, AppAccessPolicy access, RateLimiter limiter) {
    this.traces = traces;
    this.access = access;
    this.limiter = limiter;
  }

  /** A trace (14 §4.7). */
  @GetMapping(value = "/api/v1/traces/{traceId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "traces_get",
      summary = "A distributed trace",
      description =
          "Searched range: window (1h, 24h, 7d, 30d until now; 30d counts against the heavy"
              + " bucket, 10 a minute), else ?ts +-1 h, else the time in an Argos-generated id +-1"
              + " h (then 24 h when nothing is there), else the last 24 h. At most 500"
              + " transactions (meta.truncated). meta.retention_exceeded is true when the searched"
              + " range starts before the raw retention (argos.retention.xlog). 404 not_found"
              + " (trace_id/not_in_window) when the range holds none.")
  @ArgosLimits(rangeMax = "30d", rowsMax = TraceService.MAX_NODES, timeoutS = 10)
  @ArgosSource({"xlog", "profile_step", "postgres:dictionary", "postgres:apps"})
  public ResponseEnvelope<TraceView> trace(
      @PathVariable("traceId") String traceId,
      @Parameter(description = "A time of the trace (ISO-8601 UTC): search +-1 h around it")
          @RequestParam(value = "ts", required = false)
          String ts,
      @Parameter(
              description = "1h, 24h, 7d or 30d until now",
              schema = @Schema(allowableValues = {"1h", "24h", "7d", "30d"}))
          @RequestParam(value = "window", required = false)
          String window,
      HttpServletRequest request,
      HttpServletResponse response) {
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    if (principal == null) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
    String trace = Ids.traceId("trace_id", traceId);
    Instant hint = QueryLimits.optionalTime("ts", ts);
    if (window != null) {
      TraceService.window(window);
      if (window.equals(HEAVY_WINDOW)) {
        chargeHeavy(principal, response);
      }
    }
    TraceService.Result result = traces.trace(trace, hint, window);
    for (TraceView.Node n : result.view().nodes()) {
      access.check(principal, n.appId());
    }
    return ResponseEnvelope.builder(result.view())
        .range(result.view().window().from(), result.view().window().to())
        .source(Source.RAW)
        .truncated(result.truncated())
        .retentionExceeded(result.retentionExceeded())
        .build();
  }

  /** One token of the user's heavy bucket; 429 {@code rate_limited} when none is left. */
  private void chargeHeavy(ArgosPrincipal principal, HttpServletResponse response) {
    if (!limiter.enforces(RateLimitBucket.HEAVY)) {
      return;
    }
    RateLimiter.Decision decision =
        limiter.tryConsume(RateLimitBucket.HEAVY, "user:" + principal.userId());
    if (!decision.allowed()) {
      ApiException error = new ApiException(ErrorCode.RATE_LIMITED);
      decision.headers().forEach(error::header);
      throw error;
    }
    decision.writeHeaders(response);
  }
}
