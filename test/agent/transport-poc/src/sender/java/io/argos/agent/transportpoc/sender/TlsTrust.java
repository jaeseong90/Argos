/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.sender;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * JDK-only TLS trust for the comparison senders (10 §10.3 "SslProvider.JDK"): the CA certificates
 * of {@code server.tls.ca-file} (PEM), or the JDK default trust store when no file is configured.
 * Host name verification stays on (HTTPS endpoint identification) in every transport.
 */
public final class TlsTrust {
  private TlsTrust() {}

  /** Trust managers for {@code caFile} (PEM, one or more certificates) or the JDK defaults. */
  public static TrustManagerFactory trustManagers(File caFile) throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    if (caFile == null) {
      factory.init((KeyStore) null);
      return factory;
    }
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    CertificateFactory certificates = CertificateFactory.getInstance("X.509");
    try (InputStream in = new FileInputStream(caFile)) {
      Collection<? extends Certificate> all = certificates.generateCertificates(in);
      int index = 0;
      for (Certificate certificate : all) {
        store.setCertificateEntry("argos-ca-" + index++, certificate);
      }
      if (index == 0) {
        throw new IllegalArgumentException("no certificate in server.tls.ca-file");
      }
    }
    factory.init(store);
    return factory;
  }

  /** A TLS context that trusts {@code caFile} (or the JDK defaults); no client certificate. */
  public static SSLContext context(File caFile) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers(caFile).getTrustManagers(), null);
    return context;
  }
}
