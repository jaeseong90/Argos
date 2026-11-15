/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A test PKI generated at run time with the JDK's {@code keytool} (nothing is committed, gitleaks
 * 0): a CA ({@code CN=Argos Test CA}, basic constraints CA) and a server certificate signed by it,
 * with the requested DNS and IP subject alternative names and {@code serverAuth}, both valid for 2
 * days. The server key is written as unencrypted PKCS#8 PEM ({@code argos.collector.tls.key-file},
 * 16 §3.1); the certificates as PEM ({@code cert-file}, and the CA for the agents' {@code
 * server.tls.ca-file}).
 *
 * <p>Keystore passwords are random per call and passed to {@code keytool} through environment
 * variables ({@code -storepass:env}), never on its command line or in a log.
 */
public final class TestCertificates {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long KEYTOOL_TIMEOUT_S = 120;

  private TestCertificates() {}

  /** Key algorithm of both certificates. */
  public enum KeyAlgorithm {
    /** EC P-256: ECDHE-ECDSA suites of 16 §3.2. */
    EC,
    /** RSA 2048: ECDHE-RSA suites of 16 §3.2. */
    RSA
  }

  /**
   * The generated files.
   *
   * @param caCertificate PEM of the CA certificate (trust anchor of the fake agent)
   * @param serverCertificate PEM of the server certificate followed by the CA certificate (chain)
   * @param serverKey PEM of the server's unencrypted PKCS#8 private key
   */
  public record TestPki(Path caCertificate, Path serverCertificate, Path serverKey) {}

  /** An EC PKI for {@code localhost} and {@code 127.0.0.1} in {@code directory}. */
  public static TestPki generate(Path directory) {
    return generate(directory, KeyAlgorithm.EC, List.of("localhost"), List.of("127.0.0.1"));
  }

  /**
   * A PKI in {@code directory} (created if needed; the server key file is readable by its owner
   * only where POSIX permissions exist).
   *
   * @param dnsNames subject alternative DNS names of the server certificate (the first is its CN)
   * @param ipAddresses subject alternative IP addresses
   */
  public static TestPki generate(
      Path directory, KeyAlgorithm algorithm, List<String> dnsNames, List<String> ipAddresses) {
    try {
      Files.createDirectories(directory);
      Path caStore = directory.resolve("ca.p12");
      Path serverStore = directory.resolve("server.p12");
      Path csr = directory.resolve("server.csr");
      Path signed = directory.resolve("server-leaf.pem");
      Path caPem = directory.resolve("ca.pem");
      Path chainPem = directory.resolve("server.pem");
      Path keyPem = directory.resolve("server-key.pem");
      for (Path p : List.of(caStore, serverStore, csr, signed, caPem, chainPem, keyPem)) {
        Files.deleteIfExists(p);
      }
      String caPass = password();
      String serverPass = password();
      Map<String, String> env = new HashMap<>();
      env.put("ARGOS_TEST_CA_PASS", caPass);
      env.put("ARGOS_TEST_SERVER_PASS", serverPass);
      List<String> keyArgs = keyArgs(algorithm);

      List<String> ca = new ArrayList<>(List.of("-genkeypair", "-alias", "ca"));
      ca.addAll(keyArgs);
      ca.addAll(
          List.of(
              "-dname",
              "CN=Argos Test CA",
              "-ext",
              "bc:c",
              "-validity",
              "2",
              "-keystore",
              caStore.toString(),
              "-storetype",
              "PKCS12",
              "-storepass:env",
              "ARGOS_TEST_CA_PASS"));
      keytool(ca, env);

      List<String> server = new ArrayList<>(List.of("-genkeypair", "-alias", "server"));
      server.addAll(keyArgs);
      server.addAll(
          List.of(
              "-dname",
              "CN=" + (dnsNames.isEmpty() ? "localhost" : dnsNames.get(0)),
              "-validity",
              "2",
              "-keystore",
              serverStore.toString(),
              "-storetype",
              "PKCS12",
              "-storepass:env",
              "ARGOS_TEST_SERVER_PASS"));
      keytool(server, env);

      keytool(
          List.of(
              "-certreq",
              "-alias",
              "server",
              "-keystore",
              serverStore.toString(),
              "-storepass:env",
              "ARGOS_TEST_SERVER_PASS",
              "-file",
              csr.toString()),
          env);

      List<String> san = new ArrayList<>();
      dnsNames.forEach(d -> san.add("dns:" + d));
      ipAddresses.forEach(i -> san.add("ip:" + i));
      List<String> sign =
          new ArrayList<>(
              List.of(
                  "-gencert",
                  "-alias",
                  "ca",
                  "-keystore",
                  caStore.toString(),
                  "-storepass:env",
                  "ARGOS_TEST_CA_PASS",
                  "-infile",
                  csr.toString(),
                  "-outfile",
                  signed.toString(),
                  "-rfc",
                  "-validity",
                  "2",
                  "-ext",
                  "EKU=serverAuth",
                  "-ext",
                  algorithm == KeyAlgorithm.RSA
                      ? "KU=digitalSignature,keyEncipherment"
                      : "KU=digitalSignature"));
      if (!san.isEmpty()) {
        sign.add("-ext");
        sign.add("SAN=" + String.join(",", san));
      }
      keytool(sign, env);

      keytool(
          List.of(
              "-exportcert",
              "-rfc",
              "-alias",
              "ca",
              "-keystore",
              caStore.toString(),
              "-storepass:env",
              "ARGOS_TEST_CA_PASS",
              "-file",
              caPem.toString()),
          env);

      Files.writeString(
          chainPem,
          Files.readString(signed, StandardCharsets.US_ASCII)
              + Files.readString(caPem, StandardCharsets.US_ASCII),
          StandardCharsets.US_ASCII);
      writePrivateKey(serverStore, serverPass, keyPem);
      for (Path p : List.of(caStore, serverStore, csr, signed)) {
        Files.deleteIfExists(p);
      }
      return new TestPki(caPem, chainPem, keyPem);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> keyArgs(KeyAlgorithm algorithm) {
    return algorithm == KeyAlgorithm.EC
        ? List.of("-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA")
        : List.of("-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA");
  }

  private static void writePrivateKey(Path store, String password, Path pem) throws IOException {
    try (InputStream in = Files.newInputStream(store)) {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(in, password.toCharArray());
      Key key = keyStore.getKey("server", password.toCharArray());
      if (!(key instanceof PrivateKey privateKey)) {
        throw new IOException("No private key in the generated server keystore");
      }
      String body =
          Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey.getEncoded());
      Files.writeString(
          pem,
          "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
          StandardCharsets.US_ASCII);
      if (pem.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(
            pem, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      }
    } catch (GeneralSecurityException e) {
      throw new IOException("Cannot read the generated server key", e);
    }
  }

  private static void keytool(List<String> args, Map<String, String> env) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(keytoolPath().toString());
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().putAll(env);
    Process process = builder.start();
    String output;
    try (InputStream in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try {
      if (!process.waitFor(KEYTOOL_TIMEOUT_S, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("keytool " + args.get(0) + " timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IOException("Interrupted while waiting for keytool", e);
    }
    if (process.exitValue() != 0) {
      throw new IOException(
          "keytool "
              + args.get(0)
              + " failed (exit "
              + process.exitValue()
              + "): "
              + output.strip());
    }
  }

  private static Path keytoolPath() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
  }

  private static String password() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
