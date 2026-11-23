/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.ingest.IngestStore.ApdexChange;
import io.argos.server.ingest.IngestStore.OverrideLimitException;
import io.argos.server.session.api.ApdexOverrides;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Changes of the Apdex T (D-17, 12 §3.5 (5), WO-27): the app's T ({@code apps.apdex_t_ms}) and the
 * service overrides ({@code service_apdex_overrides}). A change
 *
 * <ol>
 *   <li>updates PostgreSQL and writes one {@code events} row of type {@code apdex_t_change} (API
 *       type {@code apdex}; detail {@code old_t_ms}, {@code new_t_ms}, {@code service_id}) in one
 *       transaction, the marker of the dashboard and the events API (13 §4.2);
 *   <li>reloads the {@link ApdexStamper} at once, so that batches received from then on are stamped
 *       with the new T (forward only: rows already received keep theirs, also when the WAL replays
 *       them).
 * </ol>
 *
 * <p>Nothing here is retroactive: re-stamping stored rows is the operator command {@code
 * argos-admin apdex reapply} (D-17 "재구축으로 소급"). The HTTP endpoints are WO-26's ({@code
 * admin.apps.AppAdminService}): {@code PATCH /apps/{appId}} calls {@link #setAppT}, {@code PUT} and
 * {@code DELETE /apps/{appId}/services/{serviceId}/apdex} call {@link #setServiceT} and {@link
 * #clearServiceT} (ADR-0010 C-220 (1)); they record {@code APP_UPDATED} in the audit log. The
 * operator command's audit event belongs to the 16 §11.1 catalog (WO-28); a change is also one INFO
 * log line.
 */
@Component
public class ApdexChangeService {

  private static final Logger LOG = LoggerFactory.getLogger(ApdexChangeService.class);

  private final IngestStore store;
  private final ApdexStamper stamper;
  private final Clock clock;

  @Autowired
  public ApdexChangeService(IngestStore store, ApdexStamper stamper) {
    this(store, stamper, Clock.systemUTC());
  }

  /**
   * @param store the PostgreSQL side
   * @param stamper the receipt stamp, reloaded after each change
   * @param clock the time of the change events
   */
  public ApdexChangeService(IngestStore store, ApdexStamper stamper, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.stamper = Objects.requireNonNull(stamper, "stamper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Sets the Apdex T of {@code appId}.
   *
   * @param tMs the new T, 1 to 65,535 ms
   * @param actor the user who changes it, {@code null} for an operator command
   * @return the change, empty when the app does not exist
   * @throws IllegalArgumentException for a T outside [1, 65535]
   * @throws IllegalStateException when PostgreSQL fails
   */
  public Optional<ApdexChange> setAppT(int appId, int tMs, @Nullable UUID actor) {
    checkT(tMs);
    try {
      return done(store.changeAppApdex(appId, tMs, actor, clock.instant()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("The Apdex T of app " + appId + " was not changed", e);
    }
  }

  /**
   * Sets the Apdex T override of service {@code serviceId} (the SERVICE dictionary key) of {@code
   * appId}.
   *
   * @param tMs the new T, 1 to 65,535 ms
   * @param actor the user who changes it, {@code null} for an operator command
   * @return the change, empty when the app does not exist
   * @throws IllegalArgumentException for a T outside [1, 65535]
   * @throws OverrideLimitException when the app already has {@link ApdexOverrides#MAX_PER_APP}
   *     overrides and this would be a new one
   * @throws IllegalStateException when PostgreSQL fails
   */
  public Optional<ApdexChange> setServiceT(int appId, long serviceId, int tMs, @Nullable UUID actor)
      throws OverrideLimitException {
    checkT(tMs);
    return service(appId, serviceId, tMs, actor);
  }

  /**
   * Removes the Apdex T override of service {@code serviceId}: its transactions get the app's T
   * again.
   *
   * @return the change, empty when the app does not exist
   * @throws IllegalStateException when PostgreSQL fails
   */
  public Optional<ApdexChange> clearServiceT(int appId, long serviceId, @Nullable UUID actor) {
    try {
      return service(appId, serviceId, null, actor);
    } catch (OverrideLimitException e) {
      throw new IllegalStateException("removing an override cannot exceed the limit", e);
    }
  }

  private Optional<ApdexChange> service(
      int appId, long serviceId, @Nullable Integer tMs, @Nullable UUID actor)
      throws OverrideLimitException {
    try {
      return done(
          store.changeServiceApdex(
              appId, serviceId, tMs, actor, clock.instant(), ApdexOverrides.MAX_PER_APP));
    } catch (OverrideLimitException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "The Apdex T override of a service of app " + appId + " was not changed", e);
    }
  }

  private Optional<ApdexChange> done(Optional<ApdexChange> change) {
    change
        .filter(ApdexChange::changed)
        .ifPresent(
            c -> {
              boolean reloaded = stamper.refresh();
              LOG.info(
                  "Apdex T of app {}{} changed from {} ms to {} ms (event {}); batches received"
                      + " from now on use it{}",
                  c.appId(),
                  c.serviceId() == null ? "" : ", service " + Long.toUnsignedString(c.serviceId()),
                  c.oldTMs(),
                  c.newTMs(),
                  c.eventId(),
                  reloaded ? "" : " (the stamp reloads within 5 s: PostgreSQL did not answer now)");
            });
    return change;
  }

  private static void checkT(int tMs) {
    if (tMs < ApdexOverrides.MIN_T_MS || tMs > ApdexOverrides.MAX_T_MS) {
      throw new IllegalArgumentException("Apdex T " + tMs + " ms outside [1, 65535]");
    }
  }
}
