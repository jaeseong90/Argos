/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.xlog;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.openapi.ArgosUnsignedId;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * XLog (14 §4.5, F-U02): the points of a range in columns and the summary of a dragged rectangle.
 *
 * <p>The points response is written here: JSON with Spring's mapper (the untrusted-string rules of
 * 14 §2.10 apply) and gzip-compressed when the client accepts gzip and the JSON is larger than 2 KB
 * (14 §4.5 "응답 gzip", DSN-14-08). Compression is limited to this endpoint: its body holds no
 * secret, so no compression side channel applies.
 */
@RestController
@RequiresRole(Role.VIEWER)
public class XlogController {

  /** Bodies larger than this are compressed. */
  static final int GZIP_MIN_BYTES = 2 * 1024;

  private static final Set<String> COLOR_BY = Set.of("status", "instance", "service");

  private final XlogService xlog;
  private final AppAccessPolicy access;
  private final JsonMapper json;

  public XlogController(XlogService xlog, AppAccessPolicy access, JsonMapper json) {
    this.xlog = xlog;
    this.access = access;
    this.json = json;
  }

  /** The points of a range (14 §4.5). */
  @GetMapping(value = "/api/v1/apps/{appId}/xlog", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "xlog_get",
      summary = "XLog points of a range in columns",
      description =
          "Range at most 24 h. With more points than limit (at most 100,000) the server samples"
              + " deterministically (A-5): every error, every slow point (elapsed_ms >="
              + " meta.slow_threshold_ms) and cityHash64(tx_id) % N = 0, N = ceil(total / limit);"
              + " meta.shown, meta.total and meta.sampling.rule say so, and the same request"
              + " returns the same points. Inside the limit errors and slow points come first"
              + " (sampled points beyond it are dropped evenly over the range); only when errors"
              + " and slow points alone exceed limit are some of them left out. meta.truncated is"
              + " true when more points satisfy the sampling rule than limit, so that some of them"
              + " (sampled points first) were left out: narrow the range or raise limit."
              + " 64-bit ids are decimal strings. The"
              + " body is gzip-compressed for clients that accept it (above 2 KB). meta.outages"
              + " lists the collection outages of the range (the dashboard's spans; instance state"
              + " events are recorded from WO-22 on, so before it only server outages appear).",
      responses =
          @ApiResponse(
              responseCode = "200",
              description = "The points",
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema = @Schema(implementation = XlogPoints.Response.class))))
  @ArgosLimits(rangeMax = "24h", rowsMax = XlogService.MAX_LIMIT, timeoutS = 10)
  @ArgosSource({
    "xlog",
    "postgres:dictionary",
    "postgres:apps",
    "postgres:instances",
    "postgres:events"
  })
  public ResponseEntity<byte[]> points(
      @PathVariable("appId") int appId,
      @Parameter(description = "Range start, ISO-8601 UTC") @RequestParam("from") String from,
      @Parameter(description = "Range end (exclusive)") @RequestParam("to") String to,
      @Parameter(description = "HTTP (default), BATCH, MESSAGE, ALL")
          @RequestParam(value = "kind", required = false)
          String kind,
      @Parameter(description = "Service keys (decimal), repeatable, at most 100")
          @RequestParam(value = "service", required = false)
          @ArgosUnsignedId
          List<String> service,
      @Parameter(description = "Instances, repeatable, at most 100")
          @RequestParam(value = "instance", required = false)
          List<Integer> instance,
      @RequestParam(value = "error", required = false) Boolean error,
      @RequestParam(value = "min_ms", required = false) Long minMs,
      @RequestParam(value = "max_ms", required = false) Long maxMs,
      @Parameter(description = "Most points, 1 to 100,000 (default 100,000)")
          @RequestParam(value = "limit", required = false)
          Integer limit,
      @Parameter(description = "status (default), instance or service: a UI hint, validated only")
          @RequestParam(value = "color_by", required = false)
          String colorBy,
      HttpServletRequest request) {
    access.check(ArgosPrincipal.current().orElse(null), appId);
    if (colorBy != null && !COLOR_BY.contains(colorBy)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("color_by", "unsupported_value");
    }
    int max = QueryLimits.limit("limit", limit, XlogService.MAX_LIMIT, XlogService.MAX_LIMIT);
    XlogService.Filter filter =
        xlog.filter(appId, from, to, kind, service, instance, error, minMs, maxMs);
    ResponseEnvelope<XlogPoints> envelope = xlog.points(filter, max);
    byte[] body = json.writeValueAsBytes(envelope);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
    if (body.length > GZIP_MIN_BYTES && acceptsGzip(request)) {
      return response.header(HttpHeaders.CONTENT_ENCODING, "gzip").body(gzip(body));
    }
    return response.body(body);
  }

  /** The summary of a dragged rectangle (14 §4.5). */
  @PostMapping(
      value = "/api/v1/apps/{appId}/xlog/summary",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "xlog_summarize",
      summary = "Summary of a dragged XLog rectangle",
      description =
          "count, then the 10 largest groups by service, instance, exception class (failed"
              + " transactions) and HTTP status. Range at most 24 h. A read-only query whose"
              + " filters travel in the body.")
  @ArgosAudit(none = "A read-only query; the POST only carries the filters")
  @ArgosLimits(rangeMax = "24h", timeoutS = 10)
  @ArgosSource({"xlog", "postgres:dictionary", "postgres:instances"})
  public ResponseEnvelope<XlogSummary> summary(
      @PathVariable("appId") int appId, @RequestBody XlogSummary.Request body) {
    access.check(ArgosPrincipal.current().orElse(null), appId);
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("body", "missing");
    }
    XlogService.Filter filter =
        xlog.filter(
            appId,
            body.from(),
            body.to(),
            body.kind(),
            body.service(),
            body.instance(),
            body.error(),
            body.minMs(),
            body.maxMs());
    return xlog.summary(filter);
  }

  /** Whether {@code Accept-Encoding} lists gzip with a non-zero quality. */
  static boolean acceptsGzip(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.ACCEPT_ENCODING);
    if (header == null) {
      return false;
    }
    for (String part : header.split(",", -1)) {
      String[] fields = part.strip().split(";", -1);
      String coding = fields[0].strip().toLowerCase(Locale.ROOT);
      if (!coding.equals("gzip") && !coding.equals("x-gzip") && !coding.equals("*")) {
        continue;
      }
      boolean zero = false;
      for (int i = 1; i < fields.length; i++) {
        String p = fields[i].strip().toLowerCase(Locale.ROOT);
        if (p.startsWith("q=")) {
          try {
            zero = Double.parseDouble(p.substring(2)) <= 0;
          } catch (NumberFormatException e) {
            zero = true;
          }
        }
      }
      if (!zero) {
        return true;
      }
    }
    return false;
  }

  /**
   * gzip at the fastest deflate level: 100,000 points (about 6 MB of JSON) compress about five to
   * one at a fraction of the default level's CPU time (14 §12 #3 reference in the report).
   */
  static byte[] gzip(byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, body.length / 4));
    try (GZIPOutputStream gz = new FastGzipOutputStream(out)) {
      gz.write(body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** A {@link GZIPOutputStream} with {@link Deflater#BEST_SPEED}. */
  private static final class FastGzipOutputStream extends GZIPOutputStream {

    FastGzipOutputStream(ByteArrayOutputStream out) throws IOException {
      super(out, 64 * 1024);
      def.setLevel(Deflater.BEST_SPEED);
    }
  }
}
