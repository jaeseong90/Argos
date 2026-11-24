/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.tls;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.ops.tls.CertReloader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual certificate reload of the gRPC listener (14 §4.14, 16 §3.1 {@code POST /admin/tls/reload},
 * SEC-09; WO-28), administrators only: the certificate and key files are loaded again and new
 * handshakes use them; connections already open keep theirs. A reload that cannot load the files
 * keeps the previous certificate and answers 409 {@code invalid_state} (the reason names files and
 * settings, never key material). Every attempt is audited as {@code TLS_RELOADED}.
 */
@RestController
@RequiresRole(Role.ADMIN)
public class TlsController {

  private final CertReloader certificates;

  public TlsController(CertReloader certificates) {
    this.certificates = certificates;
  }

  /**
   * The outcome of a reload.
   *
   * @param listener {@code grpc}
   * @param notAfter end of validity of the certificate new handshakes use
   * @param reloaded true: new handshakes use the files' certificate
   * @param serial serial number of that certificate, lower-case hex
   */
  public record TlsReloadView(
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"grpc"})
          String listener,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("not_after") Instant notAfter,
      @Schema(requiredMode = RequiredMode.REQUIRED) boolean reloaded,
      @Schema(description = "Serial number of the certificate, lower-case hex")
          @Nullable String serial) {}

  /** Loads the gRPC listener's certificate and key files again. */
  @PostMapping("/api/v1/admin/tls/reload")
  @Operation(operationId = "admin_tls_reload")
  @ArgosAudit(AuditEvent.TLS_RELOADED)
  public Envelope<TlsReloadView> reload(HttpServletRequest request) {
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    CertReloader.ReloadResult r = certificates.reload(Actor.user(principal, request), "api");
    if (!r.reloaded() || r.certificate() == null) {
      throw new ApiException(
          ErrorCode.INVALID_STATE,
          r.error() == null ? "The TLS certificate was not reloaded" : r.error());
    }
    return Envelope.of(
        new TlsReloadView(
            r.listener(),
            r.certificate().notAfter().truncatedTo(ChronoUnit.MILLIS),
            true,
            r.certificate().serial()));
  }
}
