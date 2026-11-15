/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.storage.query.TableLevel;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The debug header of 14 §3: ADMIN only, ignored for everybody else. */
@Tag("req:DSN-14-06")
class QuerySourcesTest {

  private static Optional<ArgosPrincipal> principal(Role role) {
    return Optional.of(
        new ArgosPrincipal(UUID.randomUUID(), "u", UUID.randomUUID(), role, Set.of(), false));
  }

  private static MockHttpServletRequest request(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(QuerySources.DEBUG_HEADER, value);
    return request;
  }

  @Test
  @DisplayName("T-API-003a X-Argos-Debug-Source forces the level for ADMIN only")
  void adminOnly() {
    assertThat(QuerySources.forcedLevel(request("1m"), principal(Role.ADMIN)))
        .contains(TableLevel.MINUTE);
    assertThat(QuerySources.forcedLevel(request("RAW"), principal(Role.ADMIN)))
        .contains(TableLevel.RAW);
    assertThat(QuerySources.forcedLevel(request("1h"), principal(Role.ADMIN)))
        .contains(TableLevel.HOUR);
    assertThat(QuerySources.forcedLevel(request("1m"), principal(Role.OPERATOR))).isEmpty();
    assertThat(QuerySources.forcedLevel(request("1m"), principal(Role.VIEWER))).isEmpty();
    assertThat(QuerySources.forcedLevel(request("1m"), Optional.empty())).isEmpty();
    assertThat(QuerySources.forcedLevel(new MockHttpServletRequest(), principal(Role.ADMIN)))
        .isEmpty();
    assertThat(QuerySources.forcedLevel(null, principal(Role.ADMIN))).isEmpty();
    // A non-admin's unknown value is ignored too; only an admin is told it is wrong.
    assertThat(QuerySources.forcedLevel(request("5m"), principal(Role.VIEWER))).isEmpty();
    assertThatThrownBy(() -> QuerySources.forcedLevel(request("5m"), principal(Role.ADMIN)))
        .isInstanceOf(ApiException.class);
  }
}
