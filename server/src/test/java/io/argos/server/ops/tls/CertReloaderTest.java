/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditService;
import io.argos.server.collector.grpc.ReloadableSslContext;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.management.MeterNames;
import io.argos.server.testing.fakeagent.TestCertificates;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.ssl.SslContext;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The certificate reloader without a server (T-SEC-09 unit part; 16 §3.1, §3.3, 12 §9.5; WO-28):
 * the listener context is a {@link ReloadableSslContext} whose current context a reload swaps, the
 * expiry gauges follow the loaded certificate, and a failed reload keeps the previous one without
 * key material in the reason. The server-level cases (handshakes, file watch, API, audit) are in
 * {@code CertReloadSecurityTest}.
 */
@Tag("req:SEC-09")
class CertReloaderTest {

  @Test
  @DisplayName(
      "T-SEC-09 reload swaps the context of new handshakes (same listener context object), the"
          + " expiry gauges follow the new certificate, a failed reload keeps the previous one and"
          + " names no key material")
  void reloadSwapsAndKeepsOnFailure(@TempDir Path dir) throws Exception {
    TestCertificates.TestPki first = TestCertificates.generate(dir.resolve("first"));
    TestCertificates.TestPki second = TestCertificates.generate(dir.resolve("second"));
    Path cert = dir.resolve("live/server.pem");
    Path key = dir.resolve("live/server-key.pem");
    Files.createDirectories(cert.getParent());
    Files.copy(first.serverCertificate(), cert);
    Files.copy(first.serverKey(), key);

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("meters", registry);
    CertReloader reloader =
        new CertReloader(
            new ArgosProperties.Collector.Tls(
                true,
                cert.toString(),
                key.toString(),
                null,
                new ArgosProperties.Collector.Tls.Reload(false)),
            dir.resolve("data/tls/" + CertReloader.REQUEST_FILE),
            beans.getBeanProvider(AuditService.class),
            beans.getBeanProvider(MeterRegistry.class),
            Clock.systemUTC());
    try {
      SslContext listener = reloader.grpcContext();
      assertThat(listener).isInstanceOf(ReloadableSslContext.class);
      assertThat(reloader.grpcContext()).isSameAs(listener);
      SslContext before = ((ReloadableSslContext) listener).current();
      assertThat(serial(reloader)).isEqualTo(leafSerial(first.serverCertificate()));
      double seconds =
          registry
              .get(MeterNames.TLS_CERT_EXPIRY_SECONDS)
              .tag("listener", CertReloader.LISTENER_GRPC)
              .gauge()
              .value();
      assertThat(seconds).isPositive();
      assertThat(registry.get(MeterNames.TLS_CERT_EXPIRY_DAYS).gauge().value())
          .isCloseTo(seconds / 86_400.0, offset(0.01));

      Files.copy(second.serverCertificate(), cert, StandardCopyOption.REPLACE_EXISTING);
      Files.copy(second.serverKey(), key, StandardCopyOption.REPLACE_EXISTING);
      CertReloader.ReloadResult ok = reloader.reload(Actor.system(), "api");
      assertThat(ok.reloaded()).isTrue();
      assertThat(ok.error()).isNull();
      assertThat(new BigInteger(ok.certificate().serial(), 16))
          .isEqualTo(leafSerial(second.serverCertificate()));
      assertThat(((ReloadableSslContext) listener).current()).isNotSameAs(before);
      assertThat(serial(reloader)).isEqualTo(leafSerial(second.serverCertificate()));

      String keyText = Files.readString(second.serverKey(), StandardCharsets.US_ASCII);
      Files.writeString(
          key, "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----\n");
      SslContext kept = ((ReloadableSslContext) listener).current();
      CertReloader.ReloadResult failed = reloader.reload(Actor.system(), "api");
      assertThat(failed.reloaded()).isFalse();
      assertThat(failed.error())
          .contains("argos.collector.tls")
          .doesNotContain("bm90IGEga2V5")
          .doesNotContain(keyText.substring(30, 60));
      assertThat(((ReloadableSslContext) listener).current()).isSameAs(kept);
      assertThat(serial(reloader)).isEqualTo(leafSerial(second.serverCertificate()));
    } finally {
      reloader.destroy();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-09 before the listener starts a reload is refused with a reason; the request file"
          + " lives in <data>/tls next to argos.wal.dir's parent; result text is JSON-escaped")
  void notStartedAndHelpers(@TempDir Path dir) {
    CertReloader reloader =
        new CertReloader(
            new ArgosProperties.Collector.Tls(
                true,
                dir.resolve("missing.pem").toString(),
                dir.resolve("missing-key.pem").toString(),
                null,
                new ArgosProperties.Collector.Tls.Reload(true)),
            dir.resolve("data/tls/" + CertReloader.REQUEST_FILE),
            null,
            null,
            Clock.systemUTC());
    CertReloader.ReloadResult r = reloader.reload(Actor.system(), "api");
    assertThat(r.reloaded()).isFalse();
    assertThat(r.certificate()).isNull();
    assertThat(r.error()).contains("not started");
    assertThat(reloader.current()).isEmpty();
    assertThat(reloader.expirySeconds()).isNull();
    assertThat(CertReloader.jsonEscape("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\u000ad");
  }

  private static BigInteger serial(CertReloader r) {
    return new BigInteger(r.current().orElseThrow().serial(), 16);
  }

  private static BigInteger leafSerial(Path chain) throws Exception {
    try (InputStream in = Files.newInputStream(chain)) {
      return ((X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in))
          .getSerialNumber();
    }
  }
}
