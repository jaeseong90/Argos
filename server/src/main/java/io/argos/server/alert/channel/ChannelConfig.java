/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The secret configuration of a channel, as it is encrypted into {@code config_enc} (SEC-52): the
 * URL of a webhook or Slack channel with its {@code allow_http} flag, or the recipients of an
 * e-mail channel. Decrypted only for a delivery, a test send or a change. {@link #toString()} shows
 * the masked form only.
 *
 * @param url the destination URL (webhook, Slack), or null
 * @param allowHttp whether the channel accepts an {@code http} URL (with {@code
 *     argos.alert.webhook.allow-http})
 * @param recipients the recipients (e-mail), empty otherwise
 */
public record ChannelConfig(String url, boolean allowHttp, List<String> recipients) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public ChannelConfig {
    recipients = recipients == null ? List.of() : List.copyOf(recipients);
  }

  /** A URL configuration. */
  public static ChannelConfig url(String url, boolean allowHttp) {
    return new ChannelConfig(url, allowHttp, List.of());
  }

  /** An e-mail configuration. */
  public static ChannelConfig recipients(List<String> recipients) {
    return new ChannelConfig(null, false, recipients);
  }

  /** The JSON that is encrypted. */
  public String toJson() {
    ObjectNode node = JSON.createObjectNode();
    if (url != null) {
      node.put("url", url);
      node.put("allow_http", allowHttp);
    }
    if (!recipients.isEmpty()) {
      ArrayNode array = node.putArray("recipients");
      recipients.forEach(array::add);
    }
    return JSON.writeValueAsString(node);
  }

  /** The configuration of a decrypted JSON. */
  public static ChannelConfig fromJson(String json) {
    JsonNode node = JSON.readTree(json);
    String url = node.path("url").isString() ? node.path("url").asString() : null;
    boolean allowHttp = node.path("allow_http").asBoolean(false);
    List<String> recipients = new ArrayList<>();
    for (JsonNode r : node.path("recipients")) {
      recipients.add(r.asString());
    }
    return new ChannelConfig(url, allowHttp, recipients);
  }

  /** The masked target the API shows (SEC-52). */
  public String maskedTarget() {
    return url != null ? ChannelMasking.maskUrl(url) : ChannelMasking.maskRecipients(recipients);
  }

  /** The masked JSON of {@code config_masked}. */
  public String maskedJson(ChannelType type) {
    ObjectNode node = JSON.createObjectNode();
    node.put("target", maskedTarget());
    if (type.urlTarget()) {
      node.put("allow_http", allowHttp);
    } else {
      node.put("recipients", recipients.size());
    }
    return JSON.writeValueAsString(node);
  }

  /** The domains of the recipients, lower case. */
  public List<String> recipientDomains() {
    List<String> domains = new ArrayList<>();
    for (String r : recipients) {
      domains.add(r.substring(r.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT));
    }
    return domains;
  }

  @Override
  public String toString() {
    return "ChannelConfig[" + maskedTarget() + "]";
  }
}
