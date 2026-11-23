/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.alert;

import io.argos.server.testing.fakeagent.TestCertificates;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * TLS of the fake receivers: a server {@link SSLContext} from the PEM files of a {@link
 * TestCertificates.TestPki} (EC or RSA key, PKCS#8), generated at run time; nothing is committed.
 */
public final class TestTls {

  private TestTls() {}

  /** A PKI for {@code localhost} and {@code 127.0.0.1} in a new directory under {@code parent}. */
  public static TestCertificates.TestPki pki(Path parent) {
    try {
      return TestCertificates.generate(Files.createTempDirectory(parent, "alert-pki"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The server context of {@code pki}: its key and certificate chain. */
  public static SSLContext serverContext(TestCertificates.TestPki pki) {
    try {
      Collection<? extends Certificate> chain;
      try (InputStream in = Files.newInputStream(pki.serverCertificate())) {
        chain = CertificateFactory.getInstance("X.509").generateCertificates(in);
      }
      PrivateKey key =
          privateKey(pki.serverKey(), chain.iterator().next().getPublicKey().getAlgorithm());
      char[] password = new char[] {'t', 'e', 's', 't'};
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      store.setKeyEntry("server", key, password, chain.toArray(Certificate[]::new));
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(store, password);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
      return ctx;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PrivateKey privateKey(Path pem, String algorithm)
      throws IOException, GeneralSecurityException {
    String text = Files.readString(pem, StandardCharsets.US_ASCII);
    String base64 =
        text.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(base64);
    for (String alg : List.of(algorithm, "EC", "RSA")) {
      try {
        return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
      } catch (GeneralSecurityException e) {
        // try the next algorithm
      }
    }
    throw new GeneralSecurityException("unsupported private key");
  }
}
