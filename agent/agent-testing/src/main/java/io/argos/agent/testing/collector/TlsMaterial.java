/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;

/**
 * A throw-away self-signed server certificate for the {@link FakeCollector}, made with the JDK's
 * {@code keytool} at run time (no key material in the repository: gitleaks, WO-02 report). The key
 * store and the PEM certificate live in a temporary directory that {@link #delete()} removes; the
 * key store password is random per instance and kept in memory only.
 */
final class TlsMaterial {
  static final String ALIAS = "argos-fake-collector";

  private final Path directory;
  private final Path keyStore;
  private final Path certificate;
  private final String host;
  private final char[] password;

  private TlsMaterial(
      Path directory, Path keyStore, Path certificate, String host, char[] password) {
    this.directory = directory;
    this.keyStore = keyStore;
    this.certificate = certificate;
    this.host = host;
    this.password = password;
  }

  /** The PEM certificate (the client's trust anchor, {@code server.tls.ca-file}). */
  Path certificate() {
    return certificate;
  }

  /** The DNS name in the certificate ({@code localhost} or {@code wrong.example}). */
  String host() {
    return host;
  }

  /** Generates a 2048-bit RSA certificate for {@code host} (also IP 127.0.0.1 for localhost). */
  static TlsMaterial generate(Path parent, String host) {
    try {
      Files.createDirectories(parent);
      Path directory = Files.createTempDirectory(parent, "fake-collector-tls-");
      byte[] random = new byte[16];
      new SecureRandom().nextBytes(random);
      char[] password = HexFormat.of().formatHex(random).toCharArray();
      Path store = directory.resolve("collector.p12");
      Path pem = directory.resolve("collector-ca.pem");
      String san = host.equals("localhost") ? "dns:localhost,ip:127.0.0.1" : "dns:" + host;
      keytool(
          "-genkeypair",
          "-alias",
          ALIAS,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "2",
          "-dname",
          "CN=" + host,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          new String(password),
          "-keypass",
          new String(password));
      keytool(
          "-exportcert",
          "-rfc",
          "-alias",
          ALIAS,
          "-keystore",
          store.toString(),
          "-storepass",
          new String(password),
          "-file",
          pem.toString());
      return new TlsMaterial(directory, store, pem, host, password);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The server's key managers. */
  KeyManagerFactory keyManagers() {
    try (InputStream in = Files.newInputStream(keyStore)) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(in, password);
      KeyManagerFactory factory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      factory.init(store, password);
      return factory;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot load the fake collector key store", e);
    }
  }

  /** Deletes the key store, the certificate and the directory. */
  void delete() {
    try {
      Files.deleteIfExists(keyStore);
      Files.deleteIfExists(certificate);
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void keytool(String... arguments) throws IOException {
    String exe =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            ? "keytool.exe"
            : "keytool";
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", exe).toString());
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      byte[] output = process.getInputStream().readAllBytes();
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IOException(
            "keytool " + arguments[0] + " failed: " + new String(output, StandardCharsets.UTF_8));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while running keytool", e);
    }
  }
}
