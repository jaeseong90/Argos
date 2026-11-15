/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.edge;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints of the security suite, added to the real server as an extra primary source;
 * outside {@code io.argos.server}, so the server's component scan never picks them up.
 */
@RestController
@RequestMapping("/api/v1/test-edge")
public class EdgeEndpoints {

  /** What an unexpected failure must never show: SQL, class names, paths, host names. */
  public static final String LEAKY_MESSAGE =
      "ERROR: permission denied SELECT password_hash FROM argos.users at"
          + " io.argos.server.Secret(/opt/argos/lib/argos-server.jar) host pg-internal-7:5432";

  private final AppAccessPolicy apps;

  public EdgeEndpoints(AppAccessPolicy apps) {
    this.apps = apps;
  }

  /** 200 for everybody. */
  @GetMapping("/ok")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> ok() {
    return Map.of("ok", true);
  }

  /** Needs a viewer. */
  @GetMapping("/viewer")
  @RequiresRole(Role.VIEWER)
  public Map<String, Object> viewer() {
    return Map.of("ok", true);
  }

  /** Needs an administrator. */
  @GetMapping("/admin")
  @RequiresRole(Role.ADMIN)
  public Map<String, Object> admin() {
    return Map.of("ok", true);
  }

  /** A heavy request (14 §2.7). */
  @GetMapping("/heavy")
  @RequiresRole(Role.VIEWER)
  @RateLimited(RateLimitBucket.HEAVY)
  public Map<String, Object> heavy() {
    return Map.of("ok", true);
  }

  /** Fails unexpectedly. */
  @GetMapping("/boom")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> boom() {
    throw new IllegalStateException(LEAKY_MESSAGE);
  }

  /** Answers with the error of a wire code, like a handler of a later work order would. */
  @GetMapping("/error/{code}")
  @RequiresRole(Role.PUBLIC)
  public Map<String, Object> error(@PathVariable("code") String code) {
    ErrorCode error =
        Arrays.stream(ErrorCode.values())
            .filter(c -> c.code().equals(code))
            .findFirst()
            .orElse(ErrorCode.NOT_FOUND);
    throw new ApiException(error);
  }

  /** Echoes the size of a JSON body. */
  @PostMapping("/echo")
  @RequiresRole(Role.PUBLIC)
  @ArgosAudit(none = "test")
  public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
    return Map.of("keys", body.size());
  }

  /**
   * An identifier-typed resource that belongs to {@code owner} (16 §7.11): UUIDv4 check, then the
   * owner check, like a command result of WO-26.
   */
  @GetMapping("/resources/{id}")
  @RequiresRole(Role.VIEWER)
  public Map<String, Object> resource(
      @PathVariable("id") String id, @RequestParam("owner") String owner) {
    UUID resource = ObjectAccessPolicy.requireUuidV4(id, "id");
    ObjectAccessPolicy.requireOwnerOrAdmin(
        ArgosPrincipal.current().orElseThrow(), UUID.fromString(owner));
    return Map.of("id", resource.toString());
  }

  /** An application-scoped read through the one policy hook (16 §7.11). */
  @GetMapping("/apps/{appId}")
  @RequiresRole(Role.VIEWER)
  public Map<String, Object> app(@PathVariable("appId") long appId) {
    apps.check(ArgosPrincipal.current().orElse(null), appId);
    return Map.of("app", appId);
  }
}
