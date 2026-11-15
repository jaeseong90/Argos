/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.api;

import io.argos.server.api.Envelope;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.auth.setup.SetupService;
import io.argos.server.config.ServerBuild;
import io.argos.server.storage.postgres.auth.UserRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The first administrator: {@code /api/v1/setup/*} (14 §4.1, ADR-0010 C-106 (1), SEC-47). */
@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {

  /** The header that may carry the setup token instead of the body (16 §7.8). */
  public static final String SETUP_TOKEN_HEADER = "X-Argos-Setup-Token";

  private final SetupService setup;
  private final ServerBuild build;

  public SetupController(SetupService setup, ServerBuild build) {
    this.setup = setup;
    this.build = build;
  }

  /** Whether the wizard is open, and the server version. */
  @GetMapping("/status")
  @RequiresRole(Role.PUBLIC)
  public Envelope<AuthDtos.SetupStatusResponse> status() {
    return Envelope.of(new AuthDtos.SetupStatusResponse(setup.needsSetup(), build.version()));
  }

  /** Creates the first administrator with the setup token; the response is logged in. */
  @PostMapping("/admin")
  @RequiresRole(Role.PUBLIC)
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit({AuditEvent.SETUP_COMPLETED, AuditEvent.USER_CREATED, AuditEvent.LOGIN_SUCCESS})
  public Envelope<AuthDtos.SetupAdminResponse> createAdmin(
      @RequestBody(required = false) AuthDtos.SetupAdminRequest body,
      @RequestHeader(name = SETUP_TOKEN_HEADER, required = false) String headerToken,
      HttpServletRequest request,
      HttpServletResponse response) {
    AuthDtos.SetupAdminRequest r =
        body == null ? new AuthDtos.SetupAdminRequest(null, null, null, null) : body;
    String token = r.setupToken() != null ? r.setupToken() : headerToken;
    UserRecord admin =
        setup.createAdmin(token, r.username(), r.password(), r.ipMode(), request, response);
    return Envelope.of(new AuthDtos.SetupAdminResponse(AuthDtos.UserView.of(admin)));
  }
}
