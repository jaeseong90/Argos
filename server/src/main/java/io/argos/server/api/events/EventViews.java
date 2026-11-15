/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.storage.postgres.catalog.CatalogRepository.EventRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The event and outage types of the responses (14 §4.12, §4.4, §4.15). */
public final class EventViews {

  /** The types of 14 §4.12. */
  public static final Set<String> TYPES =
      Set.of("deploy", "start", "stop", "config", "state", "apdex", "custom");

  /** Stored types that the API names differently (V001 {@code events.type}). */
  private static final Map<String, String> STORED_TO_API =
      Map.of("apdex_t_change", "apdex", "kill_switch", "config");

  /** Most characters of {@code detail} (14 §4.12: 2 KB). */
  static final int DETAIL_MAX_BYTES = 2 * 1024;

  private EventViews() {}

  /**
   * An event (14 §4.12).
   *
   * @param eventId id
   * @param ts time
   * @param type {@code deploy}, {@code start}, {@code stop}, {@code config}, {@code state}, {@code
   *     apdex} or {@code custom}
   * @param title title
   * @param version version
   * @param detail the stored detail (JSON text), at most 2 KB
   * @param detailTruncated whether the detail was cut
   * @param instanceId instance, or null
   * @param actor the user who created it, or null for automatic events
   */
  @Schema(name = "Event")
  public record Event(
      @JsonProperty("event_id") String eventId,
      Instant ts,
      String type,
      @ArgosUntrusted @Nullable String title,
      @ArgosUntrusted @Nullable String version,
      @ArgosUntrusted @Nullable String detail,
      @JsonProperty("detail_truncated") boolean detailTruncated,
      @JsonProperty("instance_id") @Nullable Integer instanceId,
      @ArgosUntrusted @Nullable String actor) {}

  /**
   * A collection outage (D-18, C-5): see {@link Outages}.
   *
   * @param from start, clipped to the range
   * @param to end, clipped to the range; null while it lasts
   * @param reason {@code server_down}, {@code session_lost} or {@code queue_overflow}
   * @param instanceId the instance, null for a server outage
   */
  @Schema(name = "Outage")
  public record Outage(
      Instant from,
      @Nullable Instant to,
      String reason,
      @JsonProperty("instance_id") @Nullable Integer instanceId) {}

  /** The API type of a stored type ({@code custom} for one the API does not know). */
  public static String apiType(String stored) {
    String s = stored == null ? "" : stored.toLowerCase(Locale.ROOT);
    String mapped = STORED_TO_API.getOrDefault(s, s);
    return TYPES.contains(mapped) ? mapped : "custom";
  }

  /** The stored types of an API type (for the {@code type[]} filter). */
  public static Set<String> storedTypes(String apiType) {
    return switch (apiType) {
      case "apdex" -> Set.of("apdex_t_change");
      case "config" -> Set.of("config", "kill_switch");
      default -> Set.of(apiType);
    };
  }

  /** The view of a stored event. */
  public static Event of(EventRecord r) {
    UntrustedStrings.Text detail = UntrustedStrings.cap(r.detailJson(), DETAIL_MAX_BYTES);
    return new Event(
        r.id().toString(),
        r.ts(),
        apiType(r.type()),
        r.title(),
        r.version(),
        detail.value(),
        detail.truncated(),
        r.instanceId(),
        r.actor());
  }

  /** The view of an outage. */
  public static Outage of(Outages.Outage o) {
    return new Outage(o.from(), o.to(), o.reason(), o.instanceId());
  }
}
