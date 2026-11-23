/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts.channels;

import io.argos.server.alert.channel.ChannelService;
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
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification channels: {@code /api/v1/alerts/channels*} (14 §4.13, 12 §5.5, 16 §8). Viewers list
 * them (targets masked, SEC-52); administrators create, change, delete, test and rotate (14 §7.1,
 * 16 §8.5 "테스트 전송은 관리자만"). The signing key rotation path follows PM question 4 (16 §8.4 names
 * {@code /admin/channels/{id}/signing-key/rotate}; 14 has no entry).
 */
@RestController
@RequestMapping("/api/v1/alerts/channels")
public class ChannelsController {

  private final ChannelService channels;

  public ChannelsController(ChannelService channels) {
    this.channels = channels;
  }

  /** The channels, by name; targets masked. */
  @GetMapping
  @RequiresRole(Role.VIEWER)
  public Envelope<List<ChannelDtos.ChannelView>> list() {
    List<ChannelService.Summary> all = channels.list();
    boolean truncated = all.size() > ChannelService.MAX_CHANNELS;
    List<ChannelService.Summary> shown =
        truncated ? all.subList(0, ChannelService.MAX_CHANNELS) : all;
    return Envelope.of(shown.stream().map(ChannelDtos.ChannelView::of).toList(), truncated);
  }

  /**
   * Creates a channel. A webhook or Slack URL must pass the destination validation (422 {@code
   * webhook_destination_blocked}); without a master key the answer is 409 {@code
   * secret_unavailable}.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRole(Role.ADMIN)
  @ArgosAudit(AuditEvent.CHANNEL_CREATED)
  public Envelope<ChannelDtos.CreatedChannelView> create(
      @RequestBody(required = false) ChannelDtos.CreateChannelRequest body,
      HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("type", "required");
    }
    ChannelService.Created created =
        channels.create(
            new ChannelService.CreateCommand(
                body.type(), body.name(), body.target(), body.secret(), body.allowHttp()),
            actor(request));
    return Envelope.of(
        new ChannelDtos.CreatedChannelView(
            ChannelDtos.ChannelView.of(created.channel()), created.signingSecret()));
  }

  /** Changes the re-entered fields of a channel. */
  @PutMapping("/{id}")
  @RequiresRole(Role.ADMIN)
  @ArgosAudit(AuditEvent.CHANNEL_UPDATED)
  public Envelope<ChannelDtos.ChannelView> update(
      @PathVariable("id") String id,
      @RequestBody(required = false) ChannelDtos.UpdateChannelRequest body,
      HttpServletRequest request) {
    UUID channelId = channelId(id);
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The request changes nothing");
    }
    return Envelope.of(
        ChannelDtos.ChannelView.of(
            channels.update(
                channelId,
                new ChannelService.UpdateCommand(
                    body.name(), body.target(), body.allowHttp(), body.enabled()),
                actor(request))));
  }

  /** Deletes a channel; 409 while a rule refers to it. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.ADMIN)
  @ArgosAudit(AuditEvent.CHANNEL_DELETED)
  public void delete(@PathVariable("id") String id, HttpServletRequest request) {
    channels.delete(channelId(id), actor(request));
  }

  /**
   * Sends a test message through the same destination validation and client as a delivery; the
   * destination's response body is never returned.
   */
  @PostMapping("/{id}/test")
  @RequiresRole(Role.ADMIN)
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(AuditEvent.CHANNEL_TEST_SENT)
  public Envelope<ChannelDtos.TestResultView> test(
      @PathVariable("id") String id, HttpServletRequest request) {
    ChannelService.TestResult result = channels.test(channelId(id), actor(request));
    return Envelope.of(
        new ChannelDtos.TestResultView(
            result.delivered(), result.statusCode(), result.durationMs(), result.error()));
  }

  /** Rotates a webhook signing key: the new secret is shown once, the old one signs 7 more days. */
  @PostMapping("/{id}/signing-key/rotate")
  @RequiresRole(Role.ADMIN)
  @ArgosAudit(AuditEvent.CHANNEL_SIGNING_KEY_ROTATED)
  public Envelope<ChannelDtos.RotatedKeyView> rotate(
      @PathVariable("id") String id, HttpServletRequest request) {
    ChannelService.Rotated rotated = channels.rotateSigningKey(channelId(id), actor(request));
    return Envelope.of(
        new ChannelDtos.RotatedKeyView(
            rotated.signingSecret(), ChannelDtos.millis(rotated.previousValidUntil())));
  }

  private static Actor actor(HttpServletRequest request) {
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    return Actor.user(principal, request);
  }

  private static UUID channelId(String text) {
    return ObjectAccessPolicy.requireUuidV4(text, "id");
  }
}
