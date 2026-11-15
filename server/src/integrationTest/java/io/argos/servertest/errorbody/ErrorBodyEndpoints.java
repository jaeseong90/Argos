/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.errorbody;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints of {@code ErrorBodyIT}, added to the real server as an extra primary source.
 * Outside {@code io.argos.server}, so the server's component scan never picks them up in other
 * tests. Every handler carries {@link RequiresRole}, as the server requires.
 */
@RestController
@RequestMapping("/api/v1/test-errors")
public class ErrorBodyEndpoints {

  /**
   * The message of the unexpected failure: everything an error body must never show (SQL, class
   * names, paths, host names).
   */
  public static final String LEAKY_MESSAGE =
      "ERROR: permission denied SELECT password_hash FROM argos.users at"
          + " io.argos.server.Secret(/opt/argos/lib/argos-server.jar) host pg-internal-7:5432";

  /** Reads a JSON body: the body limit is hit while Jackson reads it. */
  @PostMapping("/echo")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
    return Map.of("keys", body.size());
  }

  /** Fails unexpectedly. */
  @GetMapping("/boom")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> boom() {
    throw new IllegalStateException(LEAKY_MESSAGE);
  }

  /** Needs a viewer session. */
  @GetMapping("/viewer")
  @RequiresRole(Role.VIEWER)
  public Map<String, Object> viewer() {
    return Map.of("ok", true);
  }

  /** An expected API error with a detail. */
  @GetMapping("/conflict")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> conflict() {
    throw new ApiException(ErrorCode.INVALID_STATE, "The alert is already acknowledged")
        .detail("state", "acknowledged");
  }

  /** A typed query parameter. */
  @GetMapping("/number")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> number(@RequestParam("value") int value) {
    return Map.of("value", value);
  }
}
