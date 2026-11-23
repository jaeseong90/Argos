/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.TestingHooks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * TLS of the outbound notification connections (webhook, Slack, SMTP STARTTLS): the JDK's trust
 * store, TLS 1.3 and 1.2 only, and host name verification against the host name of the destination
 * (never the pinned address; {@link #clientParameters}).
 *
 * <p>A development build can trust one more CA file ({@code argos.testing.alert-ca-file}, 18 §15):
 * the in-process fake receivers of the tests use certificates of a CA generated at run time.
 * Release builds ignore it ({@link TestingHooks}).
 */
@Component
public class ChannelTls {

  private static final Logger LOG = LoggerFactory.getLogger(ChannelTls.class);

  /** Protocols offered (16 §3.2 floor: TLS 1.2). */
  static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  private final SSLContext context;

  @Autowired
  public ChannelTls(ArgosProperties properties, TestingHooks hooks) {
    this(hooks.active().map(ArgosProperties.Testing::alertCaFile).map(Path::of).orElse(null));
  }

  /**
   * The context of the JDK trust store plus, when not null, the CA certificates of {@code
   * extraCaFile} (PEM).
   */
  public ChannelTls(Path extraCaFile) {
    try {
      TrustManagerFactory system =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      system.init((KeyStore) null);
      X509TrustManager systemManager = x509(system.getTrustManagers());
      X509TrustManager manager = systemManager;
      if (extraCaFile != null) {
        X509TrustManager extra = extraManager(extraCaFile);
        manager = new EitherTrustManager(systemManager, extra);
        LOG.warn(
            "argos.testing.alert-ca-file: notification channels also trust the CA certificates of"
                + " {}",
            extraCaFile);
      }
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {manager}, null);
      this.context = ctx;
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("The TLS context of the notification channels failed", e);
    }
  }

  /** The SSL context. */
  public SSLContext context() {
    return context;
  }

  /**
   * A context that accepts any server certificate: only for {@code argos.alert.smtp.insecure=true},
   * the explicit and warned opt-out of 16 §8.5.
   */
  public SSLContext insecureContext() {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {new AcceptingTrustManager()}, null);
      return ctx;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("TLS is not available", e);
    }
  }

  /** Accepts every chain (the insecure opt-out only). */
  private static final class AcceptingTrustManager implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      throw new CertificateException("client certificates are not checked here");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
      // insecure opt-out: no verification (16 §8.5)
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }

  /**
   * Client parameters for a connection to {@code host}: TLS 1.3/1.2, SNI with the host name (not
   * for an IP literal) and, when {@code verifyHost}, HTTPS-style host name verification of the
   * certificate against {@code host}.
   */
  public SSLParameters clientParameters(String host, boolean verifyHost) {
    SSLParameters parameters = context.getDefaultSSLParameters();
    parameters.setProtocols(PROTOCOLS);
    if (verifyHost) {
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
    }
    if (host != null && io.argos.server.config.CidrRange.literal(host).isEmpty()) {
      parameters.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
    }
    return parameters;
  }

  private static X509TrustManager extraManager(Path file)
      throws GeneralSecurityException, IOException {
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    Collection<? extends Certificate> certificates;
    try (InputStream in = Files.newInputStream(file)) {
      certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
    }
    int i = 0;
    for (Certificate c : certificates) {
      store.setCertificateEntry("extra-" + i++, c);
    }
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(store);
    return x509(tmf.getTrustManagers());
  }

  private static X509TrustManager x509(TrustManager[] managers) {
    for (TrustManager m : managers) {
      if (m instanceof X509TrustManager x) {
        return x;
      }
    }
    throw new IllegalStateException("no X509TrustManager");
  }

  /** Trusts a chain when either manager does. */
  private record EitherTrustManager(X509TrustManager first, X509TrustManager second)
      implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      throw new CertificateException("client certificates are not checked here");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      try {
        first.checkServerTrusted(chain, authType);
      } catch (CertificateException e) {
        second.checkServerTrusted(chain, authType);
      }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      List<X509Certificate> all = new ArrayList<>(List.of(first.getAcceptedIssuers()));
      all.addAll(List.of(second.getAcceptedIssuers()));
      return all.toArray(X509Certificate[]::new);
    }
  }
}
