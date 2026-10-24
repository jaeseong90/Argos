/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Report mails composed with Jakarta Mail (Spring's JavaMailSender): the Thymeleaf HTML report as
 * body, the Excel workbook attached; a SHA3-256 digest, an ECDSA signature and an AES-GCM encrypted
 * archive copy of the message computed by the Bouncy Castle provider (a provider instance of the
 * app, not registered in the JVM); the message parsed back. The mails are composed only, never
 * sent: there is no SMTP server in the sample setup.
 */
@Service
public class ReportMailer {

  /** Sender and recipient domain: reserved for documentation (RFC 2606). */
  static final String SENDER = "reports@shop-heavy.example";

  private final JavaMailSender mail;
  private final ReportRenderer renderer;
  private final SpreadsheetExporter spreadsheets;
  private final BouncyCastleProvider crypto = new BouncyCastleProvider();
  private final SecureRandom random = new SecureRandom();
  private KeyPair signingKey;
  private SecretKey archiveKey;

  public ReportMailer(
      JavaMailSender mail, ReportRenderer renderer, SpreadsheetExporter spreadsheets) {
    this.mail = mail;
    this.renderer = renderer;
    this.spreadsheets = spreadsheets;
  }

  /** Composes the report mail of one order; returns its size, subject and digest. */
  public Map<String, Object> compose(Map<String, Object> order)
      throws MessagingException, IOException, GeneralSecurityException {
    MimeMessage message = mail.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    String subject = "Order report " + order.get("id");
    helper.setFrom(SENDER);
    helper.setTo("orders@shop-heavy.example");
    helper.setSubject(subject);
    helper.setText(renderer.render(order), true);
    helper.addAttachment(
        "order-" + order.get("id") + ".xlsx",
        new ByteArrayResource(spreadsheets.workbook("order", List.of(order))),
        SpreadsheetExporter.CONTENT_TYPE);
    message.saveChanges();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    message.writeTo(out);
    byte[] bytes = out.toByteArray();
    MessageDigest sha3 = MessageDigest.getInstance("SHA3-256", crypto);
    Signature signer = Signature.getInstance("SHA256withECDSA", crypto);
    KeyPair key = signingKey();
    signer.initSign(key.getPrivate());
    signer.update(bytes);
    byte[] signature = signer.sign();
    Signature verifier = Signature.getInstance("SHA256withECDSA", crypto);
    verifier.initVerify(key.getPublic());
    verifier.update(bytes);
    // The archived copy is encrypted (AES-256-GCM, Bouncy Castle) with a key of the process.
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", crypto);
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    cipher.init(Cipher.ENCRYPT_MODE, archiveKey(), new GCMParameterSpec(128, nonce));
    byte[] encrypted = cipher.doFinal(bytes);
    // Parsed back as a receiver would: subject and parts.
    MimeMessage parsed = mail.createMimeMessage(new ByteArrayInputStream(bytes));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("subject", parsed.getSubject());
    result.put("parts", ((MimeMultipart) parsed.getContent()).getCount());
    result.put("bytes", bytes.length);
    result.put("sha3", HexFormat.of().formatHex(sha3.digest(bytes)));
    result.put("signatureVerified", verifier.verify(signature));
    result.put("encryptedBytes", encrypted.length);
    result.put("provider", crypto.getName());
    return result;
  }

  /** The archive key: AES-256, generated once per process, never stored. */
  private synchronized SecretKey archiveKey() throws GeneralSecurityException {
    if (archiveKey == null) {
      KeyGenerator generator = KeyGenerator.getInstance("AES", crypto);
      generator.init(256, random);
      archiveKey = generator.generateKey();
    }
    return archiveKey;
  }

  /** The app's report signing key: EC P-256, generated once per process, never stored. */
  private synchronized KeyPair signingKey() throws GeneralSecurityException {
    if (signingKey == null) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", crypto);
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      signingKey = generator.generateKeyPair();
    }
    return signingKey;
  }

  /** Startup: one mail composed with a sample order. */
  public Map<String, Object> prepare()
      throws MessagingException, IOException, GeneralSecurityException {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", 0L);
    order.put("status", "NEW");
    order.put("amount", "0.00");
    return compose(order);
  }
}
