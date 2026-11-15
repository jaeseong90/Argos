/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An Ed25519 key of the access tokens (16 §7.1, 12 §15 #5: the JDK's own EdDSA, no JWT library).
 * The key id ({@code kid}) is derived from the public key: the first 16 base64url characters of the
 * SHA-256 of its X.509 encoding, so the same key always has the same id and a rotated key a new
 * one.
 *
 * <p>Text forms ({@code conf/jwt.key}, {@code ARGOS_JWT_KEY}): a PKCS#8 {@code PRIVATE KEY} as PEM
 * (what {@code openssl genpkey -algorithm ed25519} writes), bare base64 of the DER bytes, or base64
 * of the raw 32-byte seed (what the installer generates for {@code ARGOS_JWT_KEY}, 16 §10.6); for
 * the previous key of a rotation also an X.509 {@code PUBLIC KEY} (verification only). {@link
 * #toString()} shows the key id only.
 */
public final class JwtKey {

  /** The JWS {@code alg} of these keys (RFC 8037). */
  public static final String ALGORITHM = "EdDSA";

  private static final String JCA = "Ed25519";
  private static final Pattern ARMOR = Pattern.compile("-----(BEGIN|END) [A-Z ]+-----");
  private static final Pattern BLANKS = Pattern.compile("\\s+|\\\\n|\\\\r");
  private static final int MAX_TEXT_LENGTH = 16 * 1024;

  /** Bytes of a raw Ed25519 seed (RFC 8032 §5.1.5). */
  private static final int SEED_BYTES = 32;

  /** The DER prefix of a PKCS#8 Ed25519 private key before its 32-byte seed (RFC 8410 §10.3). */
  private static final byte[] PKCS8_ED25519_PREFIX =
      HexFormat.of().parseHex("302e020100300506032b657004220420");

  private final String kid;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;

  private JwtKey(PrivateKey privateKey, PublicKey publicKey) {
    this.privateKey = privateKey;
    this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    this.kid = SecureTokens.sha256Base64Url(publicKey.getEncoded()).substring(0, 16);
  }

  /** A new random key. */
  public static JwtKey generate() {
    try {
      KeyPair pair = KeyPairGenerator.getInstance(JCA).generateKeyPair();
      return new JwtKey(pair.getPrivate(), pair.getPublic());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 is not available in this JDK", e);
    }
  }

  /**
   * Parses the text form of a key.
   *
   * @param text PEM or base64 of a PKCS#8 private key, or (with {@code publicAllowed}) of an X.509
   *     public key
   * @param publicAllowed whether a public key (verification only) is accepted
   * @throws IllegalArgumentException with a reason that never contains key material
   */
  public static JwtKey parse(String text, boolean publicAllowed) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("the key is empty");
    }
    if (text.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("the key text is longer than " + MAX_TEXT_LENGTH);
    }
    boolean isPublic = text.contains("BEGIN PUBLIC KEY");
    if (isPublic && !publicAllowed) {
      throw new IllegalArgumentException("a private key (PKCS#8 PRIVATE KEY) is required");
    }
    String body = BLANKS.matcher(ARMOR.matcher(text).replaceAll("")).replaceAll("");
    byte[] der;
    try {
      der = Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      try {
        der = Base64.getUrlDecoder().decode(body);
      } catch (IllegalArgumentException e2) {
        throw new IllegalArgumentException("the key is neither PEM nor base64", e2);
      }
    }
    try {
      KeyFactory factory = KeyFactory.getInstance(JCA);
      if (isPublic) {
        return new JwtKey(null, factory.generatePublic(new X509EncodedKeySpec(der)));
      }
      byte[] pkcs8 = der.length == SEED_BYTES ? pkcs8OfSeed(der) : der;
      PrivateKey privateKey;
      try {
        privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
      } finally {
        if (der.length == SEED_BYTES) {
          Arrays.fill(pkcs8, (byte) 0);
        }
      }
      return new JwtKey(privateKey, derivePublic(privateKey));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException(
          "the key is not an Ed25519 "
              + (isPublic ? "X.509 public key" : "PKCS#8 private key")
              + " ("
              + e.getClass().getSimpleName()
              + ")",
          e);
    } finally {
      Arrays.fill(der, (byte) 0);
    }
  }

  /** A PKCS#8 structure around a raw Ed25519 seed (RFC 8410 §10.3). */
  private static byte[] pkcs8OfSeed(byte[] seed) {
    byte[] out = new byte[PKCS8_ED25519_PREFIX.length + seed.length];
    System.arraycopy(PKCS8_ED25519_PREFIX, 0, out, 0, PKCS8_ED25519_PREFIX.length);
    System.arraycopy(seed, 0, out, PKCS8_ED25519_PREFIX.length, seed.length);
    return out;
  }

  /** The key id ({@code kid} header). */
  public String kid() {
    return kid;
  }

  /** Whether this key can sign (a public key of a rotation can only verify). */
  public boolean canSign() {
    return privateKey != null;
  }

  /** The private key as PKCS#8 PEM (for {@code conf/jwt.key}). */
  public String toPem() {
    if (privateKey == null) {
      throw new IllegalStateException("verification-only key");
    }
    String base64 =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(privateKey.getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
  }

  /** Signs {@code data} (Ed25519, 64 bytes). */
  byte[] sign(byte[] data) {
    if (privateKey == null) {
      throw new IllegalStateException("verification-only key");
    }
    try {
      Signature signature = Signature.getInstance(JCA);
      signature.initSign(privateKey);
      signature.update(data);
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 signing failed", e);
    }
  }

  /** Whether {@code sig} is a valid signature of {@code data} by this key. */
  boolean verify(byte[] data, byte[] sig) {
    try {
      Signature signature = Signature.getInstance(JCA);
      signature.initVerify(publicKey);
      signature.update(data);
      return signature.verify(sig);
    } catch (InvalidKeyException | SignatureException e) {
      return false;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 verification failed", e);
    }
  }

  /**
   * The public key of an Ed25519 private key. The JDK has no direct API for it; the key pair
   * generator, given the private key's 32-byte seed as its only randomness, recomputes the same
   * pair (RFC 8032 §5.1.5). A signature round trip proves the result, so a JDK that behaves
   * differently fails the start instead of issuing unverifiable tokens.
   */
  static PublicKey derivePublic(PrivateKey privateKey) throws GeneralSecurityException {
    if (!(privateKey instanceof EdECPrivateKey edec) || edec.getBytes().isEmpty()) {
      throw new InvalidKeyException("not an Ed25519 private key with a seed");
    }
    byte[] seed = edec.getBytes().get();
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(JCA);
      generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(seed));
      PublicKey publicKey = generator.generateKeyPair().getPublic();
      byte[] probe = "argos-jwt-key-check".getBytes(StandardCharsets.US_ASCII);
      Signature sign = Signature.getInstance(JCA);
      sign.initSign(privateKey);
      sign.update(probe);
      byte[] sig = sign.sign();
      Signature verify = Signature.getInstance(JCA);
      verify.initVerify(publicKey);
      verify.update(probe);
      if (!verify.verify(sig)) {
        throw new InvalidKeyException("the public key could not be derived from the private key");
      }
      return publicKey;
    } finally {
      Arrays.fill(seed, (byte) 0);
    }
  }

  @Override
  public String toString() {
    return "JwtKey[kid=" + kid + (privateKey == null ? ", verify-only" : "") + "]";
  }

  /** A "random" source that returns one fixed seed: only for {@link #derivePublic}. */
  private static final class SeedRandom extends SecureRandom {

    private static final long serialVersionUID = 1L;

    private final transient byte[] seed;
    private transient boolean used;

    SeedRandom(byte[] seed) {
      this.seed = seed.clone();
    }

    @Override
    public void nextBytes(byte[] bytes) {
      if (used || bytes.length != seed.length) {
        throw new IllegalStateException("unexpected use of the seed source");
      }
      System.arraycopy(seed, 0, bytes, 0, seed.length);
      Arrays.fill(seed, (byte) 0);
      used = true;
    }
  }
}
