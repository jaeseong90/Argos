/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.tokens;

import io.argos.server.admin.token.TokenService;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.config.ArgosProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent token API (14 §4.14, 16 §2.3, SEC-01 to SEC-04), administrators only: list, issue,
 * rotate, revoke. The token is in the issuance and rotation responses once; every {@code /api/**}
 * response is {@code Cache-Control: no-store} ({@code SecurityHeadersFilter}). Token ids in paths
 * are UUIDv4 (16 §7.11).
 */
@RestController
@RequiresRole(Role.ADMIN)
public class TokensController {

  private final TokenService tokens;
  private final @Nullable String collectorAddress;

  public TokensController(TokenService tokens, ArgosProperties properties) {
    this.tokens = tokens;
    this.collectorAddress = properties.collector().publicAddress();
  }

  /** The tokens of an app, newest first (every state; no token, no hash). */
  @GetMapping("/api/v1/admin/apps/{appId}/tokens")
  public Envelope<List<TokenDtos.AgentTokenView>> list(@PathVariable("appId") int appId) {
    return Envelope.of(
        tokens.list(appId(appId)).stream().map(TokenDtos.AgentTokenView::of).toList());
  }

  /** Issues a token; the token is in this response once. */
  @PostMapping("/api/v1/admin/apps/{appId}/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit(AuditEvent.TOKEN_ISSUED)
  public Envelope<TokenDtos.IssuedTokenResponse> issue(
      @PathVariable("appId") int appId,
      @RequestBody(required = false) TokenDtos.IssueTokenRequest body,
      HttpServletRequest request) {
    return Envelope.of(
        TokenDtos.IssuedTokenResponse.of(
            tokens.issue(actor(request), appId(appId), body == null ? null : body.expiresInDays()),
            collectorAddress));
  }

  /**
   * Rotates a token: a new token (in this response once) and the old one {@code rotating} until its
   * grace ends.
   */
  @PostMapping("/api/v1/admin/tokens/{tokenId}/rotate")
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit({AuditEvent.TOKEN_ROTATED, AuditEvent.TOKEN_ISSUED})
  public Envelope<TokenDtos.IssuedTokenResponse> rotate(
      @PathVariable("tokenId") String tokenId,
      @RequestBody(required = false) TokenDtos.RotateTokenRequest body,
      HttpServletRequest request) {
    int graceDays =
        body == null || body.graceDays() == null
            ? TokenService.DEFAULT_GRACE_DAYS
            : body.graceDays();
    return Envelope.of(
        TokenDtos.IssuedTokenResponse.of(
            tokens.rotate(actor(request), tokenId(tokenId), graceDays), collectorAddress));
  }

  /** Revokes a token; its live agent sessions end with {@code UNAUTHENTICATED} at once. */
  @PostMapping("/api/v1/admin/tokens/{tokenId}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit(AuditEvent.TOKEN_REVOKED)
  public void revoke(
      @PathVariable("tokenId") String tokenId,
      @RequestBody(required = false) TokenDtos.RevokeTokenRequest body,
      HttpServletRequest request) {
    UUID id = tokenId(tokenId);
    tokens.revoke(actor(request), id, body == null ? null : body.reason());
  }

  private static Actor actor(HttpServletRequest request) {
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    return Actor.user(principal, request);
  }

  private static int appId(int appId) {
    if (appId < 1) {
      throw new ApiException(ErrorCode.NOT_FOUND, "The application does not exist");
    }
    return appId;
  }

  /** A UUIDv4 in canonical form (16 §7.11). */
  private static UUID tokenId(String text) {
    return ObjectAccessPolicy.requireUuidV4(text, "tokenId");
  }
}
