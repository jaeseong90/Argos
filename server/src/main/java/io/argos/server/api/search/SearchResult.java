/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/**
 * The global search result (14 §4.11, F-U14): matches that the UI opens by route (14 §6).
 *
 * @param type the searched type ({@code auto} resolved: {@code tx}, {@code trace} or {@code text})
 * @param matches the matches, best first
 */
@Schema(name = "SearchResult")
public record SearchResult(
    @JsonProperty("type") @Schema(requiredMode = RequiredMode.REQUIRED) String type,
    @JsonProperty("matches") @Schema(requiredMode = RequiredMode.REQUIRED) List<Match> matches) {

  /**
   * A match.
   *
   * @param type {@code tx}, {@code trace}, {@code service}, {@code sql}, {@code instance}
   * @param label what the UI shows (collected text)
   * @param target the route to open
   * @param score relevance, 0 to 1 (1 for an id)
   */
  @Schema(name = "SearchMatch")
  public record Match(
      @JsonProperty("type") @Schema(requiredMode = RequiredMode.REQUIRED) String type,
      @JsonProperty("label") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          String label,
      @JsonProperty("target") @Schema(requiredMode = RequiredMode.REQUIRED) Target target,
      @JsonProperty("score") @Schema(requiredMode = RequiredMode.REQUIRED) double score) {}

  /**
   * A UI route (14 §6), e.g. {@code /tx/:txId} with {@code {txId, ts}}.
   *
   * @param route the route pattern
   * @param params its parameters and query keys
   */
  @Schema(name = "SearchTarget")
  public record Target(
      @JsonProperty("route") @Schema(requiredMode = RequiredMode.REQUIRED) String route,
      @JsonProperty("params") @Schema(requiredMode = RequiredMode.REQUIRED) @ArgosUntrusted
          Map<String, String> params) {}
}
