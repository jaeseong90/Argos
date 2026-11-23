/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.CidrRange;
import io.argos.server.config.SecretValues;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * E-mail through the configured SMTP server (12 §5.5, 16 §8.5, SEC-54): {@code argos.alert.smtp.*}
 * gives the server, the account and the TLS policy; the channel gives the recipients. Recipients
 * outside {@code allowed-recipient-domains} (when set) are refused at save and again at every send.
 * The SMTP password is read from its file at each send and never kept or logged.
 */
public final class EmailChannel implements NotificationChannel {

  private final ArgosProperties.Alert.Smtp smtp;
  private final SmtpClient client;
  private final String heloName;
  private final String from;

  public EmailChannel(ArgosProperties.Alert.Smtp smtp, String publicUrl, SmtpClient client) {
    this.smtp = Objects.requireNonNull(smtp, "smtp");
    this.client = Objects.requireNonNull(client, "client");
    String publicHost = host(publicUrl);
    this.heloName =
        publicHost == null || CidrRange.literal(publicHost).isPresent() ? "localhost" : publicHost;
    if (smtp.from() != null) {
      this.from = smtp.from();
    } else if (smtp.username() != null && ChannelMasking.validAddress(smtp.username())) {
      this.from = smtp.username();
    } else {
      this.from = "argos@" + heloName;
    }
  }

  @Override
  public ChannelType type() {
    return ChannelType.EMAIL;
  }

  /** Whether an SMTP server is configured. */
  public boolean configured() {
    return smtp.host() != null;
  }

  /**
   * Whether every recipient's domain is allowed ({@code allowed-recipient-domains}; empty allows
   * all).
   */
  public boolean recipientsAllowed(List<String> recipients) {
    if (smtp.allowedRecipientDomains().isEmpty()) {
      return true;
    }
    for (String r : recipients) {
      String domain = r.substring(r.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
      boolean ok = false;
      for (String allowed : smtp.allowedRecipientDomains()) {
        if (domain.equals(allowed.strip().toLowerCase(Locale.ROOT))) {
          ok = true;
          break;
        }
      }
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  @Override
  public Outcome send(OpenChannel channel, ChannelMessage message) throws DeliveryException {
    if (!configured()) {
      throw new DeliveryException("smtp_not_configured");
    }
    List<String> recipients = channel.config().recipients();
    if (recipients.isEmpty() || !recipientsAllowed(recipients)) {
      throw new DeliveryException("recipient_not_allowed");
    }
    String password;
    try {
      password =
          SecretValues.resolve(
                  "argos.alert.smtp.password-file",
                  smtp.passwordFile(),
                  "argos.alert.smtp.password",
                  smtp.password())
              .orElse(null);
    } catch (SecretValues.SecretException e) {
      throw new DeliveryException("secret_unavailable");
    }
    SmtpClient.Settings settings =
        new SmtpClient.Settings(
            smtp.host(),
            smtp.port(),
            smtp.username(),
            password,
            smtp.checkServerIdentity(),
            smtp.insecure(),
            from,
            heloName);
    SmtpClient.SmtpOutcome outcome =
        client.send(settings, recipients, message.mailSubject(), message.mailBody());
    return new Outcome(outcome.replyCode(), outcome.durationMs());
  }

  private static String host(String url) {
    if (url == null) {
      return null;
    }
    try {
      String h = new URI(url.strip()).getHost();
      return h == null ? null : h.toLowerCase(Locale.ROOT);
    } catch (URISyntaxException e) {
      return null;
    }
  }
}
