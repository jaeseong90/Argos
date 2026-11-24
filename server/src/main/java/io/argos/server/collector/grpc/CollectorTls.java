/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.SSLContext;

/**
 * The TLS context of the gRPC listener (16 §3.1, §3.2, SEC-07): PEM certificate chain and PKCS#8
 * private key from {@code argos.collector.tls.cert-file} / {@code key-file}, the JDK SSL provider
 * only (no netty-tcnative), protocols TLS 1.3 and TLS 1.2, and exactly the AEAD cipher suites of 16
 * §3.2 (CBC, RSA key exchange, NULL, EXPORT, 3DES and RC4 are not in the list, so a client that
 * offers only those fails the handshake). No client certificates (mTLS is 1.x, 16 §3.6).
 *
 * <p>An encrypted PKCS#8 key (PEM header {@code BEGIN ENCRYPTED PRIVATE KEY}, for example from
 * {@code openssl pkcs8 -topk8 -v2 aes-256-cbc}) is read with the password in {@code
 * argos.collector.tls.key-password-file} (16 §3.1, ADR-0010 C-158 (5)); the password and the key
 * never appear in a message or a log line.
 */
public final class CollectorTls {

  /** 16 §3.1: TLS 1.2 and later only. */
  public static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

  /** 16 §3.2, in order of preference. */
  public static final List<String> CIPHER_SUITES =
      List.of(
          "TLS_AES_256_GCM_SHA384",
          "TLS_AES_128_GCM_SHA256",
          "TLS_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");

  private static final Pattern ENCRYPTED_PEM =
      Pattern.compile(
          "-----BEGIN ENCRYPTED PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)" // gitleaks:allow (pattern)
              + "-----END ENCRYPTED PRIVATE KEY-----");

  private static final String PBES2_OID = "1.2.840.113549.1.5.13";

  /** Key algorithms tried for a decrypted PKCS#8 key, in order. */
  private static final List<String> KEY_ALGORITHMS =
      List.of("RSA", "EC", "RSASSA-PSS", "Ed25519", "Ed448", "DSA");

  private CollectorTls() {}

  /**
   * The suites of {@link #CIPHER_SUITES} this JDK supports, in the list's order.
   *
   * @throws IllegalStateException when none is supported (the listener does not start, 16 §3.1 "비어
   *     있으면 기동 거부")
   */
  public static List<String> usableCipherSuites() {
    Set<String> supported;
    try {
      supported =
          new HashSet<>(
              Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JDK has no default TLS implementation", e);
    }
    List<String> usable = new ArrayList<>();
    for (String suite : CIPHER_SUITES) {
      if (supported.contains(suite)) {
        usable.add(suite);
      }
    }
    if (usable.isEmpty()) {
      throw new IllegalStateException(
          "The JDK supports none of the collector's TLS cipher suites (16 §3.2); the gRPC listener"
              + " does not start");
    }
    return usable;
  }

  /**
   * The server TLS context of {@code certFile} and {@code keyFile}.
   *
   * @throws IllegalStateException with an operator-facing message (paths, never key material) when
   *     a file is missing or unreadable or the context cannot be built
   */
  public static SslContext serverContext(Path certFile, Path keyFile) {
    requireReadable(certFile, "argos.collector.tls.cert-file");
    requireReadable(keyFile, "argos.collector.tls.key-file");
    List<String> suites = usableCipherSuites();
    try {
      return GrpcSslContexts.configure(
              SslContextBuilder.forServer(certFile.toFile(), keyFile.toFile()), SslProvider.JDK)
          .protocols(PROTOCOLS)
          .ciphers(suites)
          .clientAuth(ClientAuth.NONE)
          .build();
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "Cannot load the gRPC TLS certificate "
              + certFile
              + " and key "
              + keyFile
              + " (PEM certificate chain and unencrypted PKCS#8 key expected; an encrypted key"
              + " needs argos.collector.tls.key-password-file): "
              + e.getClass().getSimpleName(),
          e);
    }
  }

  /**
   * The server TLS context of {@code certFile} and {@code keyFile}; the key is an encrypted PKCS#8
   * key with the password in the first line of {@code keyPasswordFile} when that is not null.
   *
   * @throws IllegalStateException with an operator-facing message (paths and key names, never key
   *     material or the password) when a file is missing or unreadable, the password is wrong or
   *     the context cannot be built
   */
  public static SslContext serverContext(Path certFile, Path keyFile, Path keyPasswordFile) {
    if (keyPasswordFile == null) {
      return serverContext(certFile, keyFile);
    }
    requireReadable(certFile, "argos.collector.tls.cert-file");
    requireReadable(keyFile, "argos.collector.tls.key-file");
    requireReadable(keyPasswordFile, "argos.collector.tls.key-password-file");
    List<String> suites = usableCipherSuites();
    X509Certificate[] chain = certificateChain(certFile);
    PrivateKey key = encryptedPrivateKey(keyFile, keyPasswordFile);
    try {
      return GrpcSslContexts.configure(SslContextBuilder.forServer(key, chain), SslProvider.JDK)
          .protocols(PROTOCOLS)
          .ciphers(suites)
          .clientAuth(ClientAuth.NONE)
          .build();
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "Cannot build the gRPC TLS context from the certificate "
              + certFile
              + " and the encrypted key "
              + keyFile
              + ": "
              + e.getClass().getSimpleName(),
          e);
    }
  }

  /**
   * The certificates of a PEM chain file, the leaf first.
   *
   * @throws IllegalStateException when the file is unreadable or holds no certificate
   */
  public static X509Certificate[] certificateChain(Path certFile) {
    requireReadable(certFile, "argos.collector.tls.cert-file");
    try (InputStream in = Files.newInputStream(certFile)) {
      List<X509Certificate> out = new ArrayList<>();
      for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
        if (c instanceof X509Certificate x) {
          out.add(x);
        }
      }
      if (out.isEmpty()) {
        throw new IllegalStateException(
            "argos.collector.tls.cert-file " + certFile + " holds no PEM certificate");
      }
      return out.toArray(X509Certificate[]::new);
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "Cannot read the gRPC TLS certificate "
              + certFile
              + " (PEM certificate chain expected): "
              + e.getClass().getSimpleName(),
          e);
    }
  }

  private static PrivateKey encryptedPrivateKey(Path keyFile, Path passwordFile) {
    char[] password = null;
    try {
      password = password(passwordFile);
      String pem = Files.readString(keyFile, StandardCharsets.US_ASCII);
      Matcher m = ENCRYPTED_PEM.matcher(pem);
      if (!m.find()) {
        throw new IllegalStateException(
            "argos.collector.tls.key-file "
                + keyFile
                + " is not an encrypted PKCS#8 key (BEGIN ENCRYPTED PRIVATE KEY) although"
                + " argos.collector.tls.key-password-file is set");
      }
      byte[] der = Base64.getMimeDecoder().decode(m.group(1));
      EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
      String algorithm = info.getAlgName();
      if (("PBES2".equalsIgnoreCase(algorithm) || PBES2_OID.equals(algorithm))
          && info.getAlgParameters() != null) {
        // PBES2: the combined name (e.g. PBEWithHmacSHA256AndAES_256) comes from its parameters.
        algorithm = info.getAlgParameters().toString();
      }
      SecretKey secret =
          SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password));
      Cipher cipher = Cipher.getInstance(algorithm);
      cipher.init(Cipher.DECRYPT_MODE, secret, info.getAlgParameters());
      return privateKey(info.getKeySpec(cipher), keyFile);
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "Cannot decrypt the gRPC TLS key "
              + keyFile
              + " with argos.collector.tls.key-password-file "
              + passwordFile
              + " (wrong password or unsupported encryption): "
              + e.getClass().getSimpleName(),
          e);
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }
  }

  private static PrivateKey privateKey(PKCS8EncodedKeySpec spec, Path keyFile)
      throws NoSuchAlgorithmException {
    for (String algorithm : KEY_ALGORITHMS) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
        // Not this algorithm: try the next one.
      }
    }
    throw new NoSuchAlgorithmException(
        "No supported key algorithm for the decrypted key of " + keyFile);
  }

  /** The first line of {@code file}, without its line break. */
  private static char[] password(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    try {
      String text = new String(bytes, StandardCharsets.UTF_8);
      int end = text.indexOf('\n');
      String line = end < 0 ? text : text.substring(0, end);
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      if (line.isEmpty()) {
        throw new IOException("The password file is empty");
      }
      return line.toCharArray();
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static void requireReadable(Path file, String key) {
    if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
      throw new IllegalStateException(
          key
              + " "
              + file.toAbsolutePath()
              + " is missing or unreadable: the TLS gRPC listener needs it (set"
              + " argos.collector.tls.enabled=false only for a plaintext demo)");
    }
  }
}
