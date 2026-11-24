/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.tls;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

/**
 * A test CA and server certificates for {@code localhost}/{@code 127.0.0.1} generated at run time
 * with the JDK's {@code keytool} (nothing committed; keystore passwords through environment
 * variables only): leaves with a chosen validity (and start date, for an expired one), each written
 * as a PEM chain, an unencrypted PKCS#8 key and a PBES2 (PBKDF2-HMAC-SHA256, AES-256) encrypted
 * PKCS#8 key, the format of {@code openssl pkcs8 -topk8 -v2 aes-256-cbc}.
 */
final class ReloadPki {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path dir;
  private final String caPass = secret();
  private final Path caStore;
  private final Path caPem;

  /**
   * One leaf.
   *
   * @param chain PEM leaf followed by the CA
   * @param key unencrypted PKCS#8 PEM
   * @param encryptedKey encrypted PKCS#8 PEM ({@code BEGIN ENCRYPTED PRIVATE KEY})
   * @param serial the leaf's serial number
   */
  record Leaf(Path chain, Path key, Path encryptedKey, BigInteger serial) {}

  ReloadPki(Path dir) {
    this.dir = dir;
    this.caStore = dir.resolve("ca.p12");
    this.caPem = dir.resolve("ca.pem");
    try {
      Files.createDirectories(dir);
      keytool(
          List.of(
              "-genkeypair",
              "-alias",
              "ca",
              "-keyalg",
              "EC",
              "-groupname",
              "secp256r1",
              "-sigalg",
              "SHA256withECDSA",
              "-dname",
              "CN=Argos Reload Test CA",
              "-ext",
              "bc:c",
              "-validity",
              "60",
              "-keystore",
              caStore.toString(),
              "-storetype",
              "PKCS12",
              "-storepass:env",
              "ARGOS_TEST_CA_PASS"));
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
              caPem.toString()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The CA certificate (what an agent trusts). */
  Path ca() {
    return caPem;
  }

  /**
   * A new leaf signed by the CA.
   *
   * @param name file name prefix
   * @param startDate keytool start date ({@code -10d}), or null for now
   * @param validityDays days of validity from the start date
   * @param password the password of the encrypted key file
   */
  Leaf leaf(String name, String startDate, int validityDays, char[] password) {
    try {
      String pass = secret();
      Path store = dir.resolve(name + ".p12");
      Path csr = dir.resolve(name + ".csr");
      Path signed = dir.resolve(name + "-leaf.pem");
      Map<String, String> env = new HashMap<>();
      env.put("ARGOS_TEST_LEAF_PASS", pass);
      keytool(
          List.of(
              "-genkeypair",
              "-alias",
              "leaf",
              "-keyalg",
              "EC",
              "-groupname",
              "secp256r1",
              "-sigalg",
              "SHA256withECDSA",
              "-dname",
              "CN=localhost",
              "-validity",
              "2",
              "-keystore",
              store.toString(),
              "-storetype",
              "PKCS12",
              "-storepass:env",
              "ARGOS_TEST_LEAF_PASS"),
          env);
      keytool(
          List.of(
              "-certreq",
              "-alias",
              "leaf",
              "-keystore",
              store.toString(),
              "-storepass:env",
              "ARGOS_TEST_LEAF_PASS",
              "-file",
              csr.toString()),
          env);
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
                  Integer.toString(validityDays),
                  "-ext",
                  "EKU=serverAuth",
                  "-ext",
                  "KU=digitalSignature",
                  "-ext",
                  "SAN=dns:localhost,ip:127.0.0.1"));
      if (startDate != null) {
        sign.add("-startdate");
        sign.add(startDate);
      }
      keytool(sign, env);
      Path chain = dir.resolve(name + ".pem");
      Files.writeString(
          chain,
          Files.readString(signed, StandardCharsets.US_ASCII)
              + Files.readString(caPem, StandardCharsets.US_ASCII),
          StandardCharsets.US_ASCII);
      PrivateKey key = privateKey(store, pass);
      Path keyPem = dir.resolve(name + "-key.pem");
      Files.writeString(keyPem, pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);
      Path encrypted = dir.resolve(name + "-key-enc.pem");
      Files.writeString(
          encrypted,
          pem("ENCRYPTED PRIVATE KEY", encrypt(key.getEncoded(), password)),
          StandardCharsets.US_ASCII);
      BigInteger serial;
      try (InputStream in = Files.newInputStream(signed)) {
        serial =
            ((X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in))
                .getSerialNumber();
      }
      for (Path p : List.of(store, csr, signed)) {
        Files.deleteIfExists(p);
      }
      return new Leaf(chain, keyPem, encrypted, serial);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] encrypt(byte[] pkcs8, char[] password) throws GeneralSecurityException {
    byte[] salt = new byte[16];
    byte[] iv = new byte[16];
    RANDOM.nextBytes(salt);
    RANDOM.nextBytes(iv);
    String algorithm = "PBEWithHmacSHA256AndAES_256";
    SecretKey key =
        SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password));
    Cipher cipher = Cipher.getInstance(algorithm);
    cipher.init(
        Cipher.ENCRYPT_MODE, key, new PBEParameterSpec(salt, 10_000, new IvParameterSpec(iv)));
    byte[] ct = cipher.doFinal(pkcs8);
    try {
      // The PBES2 OID (1.2.840.113549.1.5.13) with the cipher's PBKDF2 and AES parameters, as
      // openssl writes it; the JDK has no OID for the combined algorithm name.
      AlgorithmParameters pbes2 = AlgorithmParameters.getInstance("PBES2");
      pbes2.init(cipher.getParameters().getEncoded());
      return new EncryptedPrivateKeyInfo(pbes2, ct).getEncoded();
    } catch (IOException e) {
      throw new GeneralSecurityException(e);
    }
  }

  private static PrivateKey privateKey(Path store, String pass)
      throws IOException, GeneralSecurityException {
    try (InputStream in = Files.newInputStream(store)) {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(in, pass.toCharArray());
      Key key = ks.getKey("leaf", pass.toCharArray());
      if (!(key instanceof PrivateKey pk)) {
        throw new IOException("No private key in the generated keystore");
      }
      return pk;
    }
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
        + "\n-----END "
        + type
        + "-----\n";
  }

  private void keytool(List<String> args) throws IOException {
    keytool(args, Map.of());
  }

  private void keytool(List<String> args, Map<String, String> extraEnv) throws IOException {
    List<String> command = new ArrayList<>();
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    command.add(
        Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool")
            .toString());
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("ARGOS_TEST_CA_PASS", caPass);
    builder.environment().putAll(extraEnv);
    Process process = builder.start();
    String output;
    try (InputStream in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try {
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("keytool " + args.get(0) + " timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for keytool", e);
    }
    if (process.exitValue() != 0) {
      throw new IOException("keytool " + args.get(0) + " failed: " + output.strip());
    }
  }

  private static String secret() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
