/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.config.ServerBuild;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The start-up warnings of relaxed SMTP TLS (16 §8.5, SEC-54). */
@Tag("req:SEC-54")
class ChannelSendersTest {

  private final List<String> warnings = new CopyOnWriteArrayList<>();
  private Logger logger;
  private AbstractAppender appender;

  @BeforeEach
  void capture() {
    logger = (Logger) LogManager.getLogger(ChannelSenders.class);
    appender =
        new AbstractAppender("wo25-capture", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
              warnings.add(event.getMessage().getFormattedMessage());
            }
          }
        };
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
  }

  @AfterEach
  void release() {
    logger.removeAppender(appender);
    appender.stop();
  }

  private void senders(Map<String, Object> smtp) {
    new ChannelSenders(
        AuthTestProperties.of(smtp),
        new DestinationValidator(
            AuthTestProperties.of(Map.of()), host -> List.of(), Clock.systemUTC()),
        new ChannelTls((Path) null),
        new ServerBuild("0.0.0-SNAPSHOT", null, null),
        Clock.systemUTC());
  }

  @Test
  @DisplayName("T-SEC-54b argos.alert.smtp.insecure=true warns at every start")
  void insecureWarns() {
    senders(
        Map.of("argos.alert.smtp.host", "mail.example.test", "argos.alert.smtp.insecure", "true"));
    assertThat(warnings).anyMatch(w -> w.contains("argos.alert.smtp.insecure=true"));
  }

  @Test
  @DisplayName(
      "T-SEC-54b the default SMTP settings start without a warning; starttls=optional alone warns"
          + " that STARTTLS stays required")
  void defaults() {
    senders(Map.of("argos.alert.smtp.host", "mail.example.test"));
    assertThat(warnings).isEmpty();
    senders(
        Map.of(
            "argos.alert.smtp.host", "mail.example.test", "argos.alert.smtp.starttls", "optional"));
    assertThat(warnings).anyMatch(w -> w.contains("STARTTLS stays required"));
  }
}
