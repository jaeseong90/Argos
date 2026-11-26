/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.argos.server.admin.apps.AppAdminService;
import io.argos.server.admin.bootstrap.AppBootstrapPhase.Outcome;
import io.argos.server.admin.token.TokenService;
import io.argos.server.admin.token.TokenService.Registration;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditService;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.config.ArgosProperties;
import io.argos.server.session.AppRef;
import io.argos.server.session.SessionRegistry;
import io.argos.server.storage.postgres.app.AppRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

/**
 * The bootstrap step (WO-41): it runs in {@code LISTENERS} before the gRPC listener, creates the
 * missing applications through the administration service (system actor), registers each token file
 * once (a restart changes nothing), and stops the start with a message that names the file and the
 * problem but never a token.
 */
@Tag("req:N-16")
@Tag("req:SEC-74")
class AppBootstrapPhaseTest {

  private static final int ORDER_APP = 101;
  private static final int PAYMENT_APP = 102;

  @TempDir Path dir;

  private final MemoryTokenStore store = new MemoryTokenStore(Set.of(ORDER_APP, PAYMENT_APP));
  private final AuditService audit = (event, actor, target, result, before, after) -> {};
  private final TokenService tokens =
      new TokenService(
          store, audit, mock(TokenLookup.class), mock(SessionRegistry.class), new MutableClock());
  private final AppRepository apps = mock(AppRepository.class);
  private final AppAdminService admin = mock(AppAdminService.class);
  private final Map<String, Integer> existing = new HashMap<>();

  private String orderToken;
  private String paymentToken;

  /** Loads Mockito's inline mock maker and the binder once, outside the duration of a test. */
  @BeforeAll
  static void warmUp() {
    assertThat(mock(AppRepository.class)).isNotNull();
    assertThat(mock(AppAdminService.class)).isNotNull();
    assertThat(Binder.get(new MockEnvironment()).bindOrCreate("argos", ArgosProperties.class))
        .isNotNull();
  }

  @BeforeEach
  void setUp() throws IOException {
    orderToken = AgentTokens.generate();
    paymentToken = AgentTokens.generate();
    write("demo_order_api_token", orderToken + "\n");
    write("demo_payment_api_token", paymentToken + "\n");
    when(apps.find(any(), any()))
        .thenAnswer(
            call -> {
              Integer id = existing.get(call.getArgument(0, String.class));
              return id == null
                  ? Optional.empty()
                  : Optional.of(new AppRef(id, call.getArgument(0), call.getArgument(1), 0));
            });
    when(admin.create(any(), eq(Actor.system())))
        .thenAnswer(
            call -> {
              AppAdminService.NewApp app = call.getArgument(0);
              int id = app.name().equals("order-api") ? ORDER_APP : PAYMENT_APP;
              existing.put(app.name(), id);
              return record(id, app);
            });
  }

  private static AppRecord record(int id, AppAdminService.NewApp app) {
    return new AppRecord(
        id,
        app.name(),
        app.env(),
        app.group(),
        app.apdexTMs(),
        0,
        app.autoRegister(),
        false,
        Instant.EPOCH,
        null,
        0,
        null,
        List.of());
  }

  private Path write(String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private String demoFile() throws IOException {
    String yaml =
        BootstrapAppsFileTest.DEMO.replace(
            "/run/secrets/", dir.toAbsolutePath().toString().replace('\\', '/') + "/");
    return write("bootstrap-apps.yaml", yaml).toString();
  }

  private AppBootstrapPhase phase(String appsFile) {
    MockEnvironment env = new MockEnvironment();
    if (appsFile != null) {
      env.setProperty("argos.bootstrap.apps-file", appsFile);
    }
    ArgosProperties properties = Binder.get(env).bindOrCreate("argos", ArgosProperties.class);
    return new AppBootstrapPhase(properties, apps, admin, tokens);
  }

  @Test
  @DisplayName(
      "T-NFR-16a the step runs in LISTENERS right after setup-bootstrap, before the gRPC listener"
          + " (lowest precedence), and does nothing without argos.bootstrap.apps-file")
  void placementAndOff() throws Exception {
    AppBootstrapPhase phase = phase(null);
    assertThat(phase.order()).isEqualTo(PhaseOrder.LISTENERS);
    assertThat(phase.getOrder())
        .isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        .isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    phase.run();
    verifyNoInteractions(apps, admin);
    assertThat(store.rows).isEmpty();
  }

  @Test
  @DisplayName(
      "T-NFR-16a demo file: both apps created by the system actor with API defaults, both tokens"
          + " stored as hash only; a restart creates nothing and registers nothing")
  void createsAndIsIdempotent() throws Exception {
    AppBootstrapPhase phase = phase(demoFile());
    List<Outcome> first = phase.apply(demoFile());
    assertThat(first)
        .extracting(Outcome::name, Outcome::appId, Outcome::appCreated, Outcome::token)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "order-api", ORDER_APP, true, Registration.REGISTERED),
            org.assertj.core.groups.Tuple.tuple(
                "payment-api", PAYMENT_APP, true, Registration.REGISTERED));
    verify(admin, times(2)).create(any(), eq(Actor.system()));
    assertThat(store.rows.values())
        .extracting(r -> r.hash())
        .containsExactlyInAnyOrder(TokenHash.of(orderToken), TokenHash.of(paymentToken));
    assertThat(store.rows.values().stream().filter(r -> r.appId() == ORDER_APP).count())
        .isEqualTo(1);

    phase.run();
    assertThat(phase.apply(demoFile()))
        .extracting(Outcome::appCreated, Outcome::token)
        .containsOnly(org.assertj.core.groups.Tuple.tuple(false, Registration.ALREADY_ACTIVE));
    verify(admin, times(2)).create(any(), any());
    assertThat(store.rows).hasSize(2);
  }

  @Test
  @DisplayName(
      "T-NFR-16a/T-SEC-74 missing or invalid files stop the start with the file and the problem,"
          + " never the token text")
  void failures() throws Exception {
    assertThatThrownBy(() -> phase(dir.resolve("absent.yaml").toString()).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("argos.bootstrap.apps-file")
        .hasMessageContaining("cannot be read");

    String invalid = write("invalid.yaml", "apps: [{name: a, env: e}]").toString();
    assertThatThrownBy(() -> phase(invalid).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("is invalid: apps[1]: 'token-file' is required");

    String appsFile = demoFile();
    Files.delete(dir.resolve("demo_payment_api_token"));
    assertThatThrownBy(() -> phase(appsFile).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("apps[2] payment-api/demo")
        .hasMessageContaining("cannot be read");

    // A secret-looking but malformed value: refused, and not repeated in the message.
    String malformed = orderToken.substring(0, 30);
    write("demo_payment_api_token", malformed + "\n");
    assertThatThrownBy(() -> phase(appsFile).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("does not hold an agent token")
        .hasMessageNotContaining(malformed);

    write("demo_payment_api_token", "\n");
    assertThatThrownBy(() -> phase(appsFile).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("is empty");

    // The order-api token given to payment-api as well: it belongs to another application.
    write("demo_payment_api_token", orderToken + "\r\n");
    assertThatThrownBy(() -> phase(appsFile).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("belongs to another application")
        .hasMessageNotContaining(orderToken);
  }
}
