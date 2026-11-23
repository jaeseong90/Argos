/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ServerBuild;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The channel implementations of this server ({@link WebhookChannel}, {@link SlackChannel}, {@link
 * EmailChannel}) with their shared HTTP and SMTP clients. Warns at start when the SMTP settings
 * relax TLS (16 §8.5, SEC-54).
 */
@Component
public class ChannelSenders {

  private static final Logger LOG = LoggerFactory.getLogger(ChannelSenders.class);

  private final Map<ChannelType, NotificationChannel> channels = new EnumMap<>(ChannelType.class);
  private final EmailChannel email;

  public ChannelSenders(
      ArgosProperties properties,
      DestinationValidator validator,
      ChannelTls tls,
      ServerBuild build,
      Clock clock) {
    Duration timeout = properties.alert().webhook().timeout();
    PinnedHttpClient http = new PinnedHttpClient(tls, timeout, "Argos/" + build.version());
    SmtpClient smtp =
        new SmtpClient(
            tls, tls.insecureContext(), HostResolver.system(DestinationValidator.DNS_TIMEOUT));
    this.email = new EmailChannel(properties.alert().smtp(), properties.web().publicUrl(), smtp);
    channels.put(ChannelType.WEBHOOK, new WebhookChannel(validator, http, clock));
    channels.put(ChannelType.SLACK, new SlackChannel(validator, http));
    channels.put(ChannelType.EMAIL, email);
    ArgosProperties.Alert.Smtp s = properties.alert().smtp();
    if (s.insecure()) {
      LOG.warn(
          "argos.alert.smtp.insecure=true: alert mails may be sent without TLS and without"
              + " certificate verification (16 §8.5)");
    } else if (s.starttls() == ArgosProperties.Alert.Smtp.StartTls.OPTIONAL) {
      LOG.warn(
          "argos.alert.smtp.starttls=optional is ignored without argos.alert.smtp.insecure=true:"
              + " STARTTLS stays required");
    }
    if (!s.insecure() && !s.checkServerIdentity()) {
      LOG.warn(
          "argos.alert.smtp.check-server-identity=false: the SMTP certificate's host name is not"
              + " verified");
    }
  }

  /** The implementation of {@code type}. */
  public NotificationChannel of(ChannelType type) {
    return channels.get(type);
  }

  /** The e-mail channel (recipient domain checks at save). */
  public EmailChannel email() {
    return email;
  }
}
