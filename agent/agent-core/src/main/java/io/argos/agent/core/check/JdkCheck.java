/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import java.util.Locale;

/**
 * Item 6 of 10 §13.3: the JDK running the check against the 01 §8 compatibility table. Java 8 needs
 * update 252 or later (TLS ALPN for the collector connection, U-4): older is FAIL, as is Java 7 and
 * older. The verified lines are 8, 11, 17, 21 and 25 of Temurin (and AdoptOpenJDK before it),
 * Oracle, Corretto and Zulu; another line or vendor (Liberica, Microsoft, Semeru/OpenJ9, a
 * distribution build) is WARN, unverified.
 */
final class JdkCheck {
  static final int ITEM = 6;

  private JdkCheck() {}

  static CheckRow row(String javaVersion, String vendor, String vmName) {
    String shown = "JDK " + javaVersion + " (" + (vendor == null ? "unknown vendor" : vendor) + ")";
    if (!jdkSupported(javaVersion)) {
      return CheckRow.of(
          ITEM,
          "jdk",
          CheckRow.Status.FAIL,
          shown
              + ": Java 8 update 252 or later is required (TLS ALPN for the collector connection)");
    }
    int feature = feature(javaVersion);
    boolean verifiedLine =
        feature == 8 || feature == 11 || feature == 17 || feature == 21 || feature == 25;
    String vendorVerdict = vendorVerdict(vendor, vmName);
    if (!verifiedLine) {
      return CheckRow.of(
          ITEM,
          "jdk",
          CheckRow.Status.WARN,
          shown + ": unverified Java version (verified: 8u252+, 11, 17, 21, 25)");
    }
    if (vendorVerdict != null) {
      return CheckRow.of(ITEM, "jdk", CheckRow.Status.WARN, shown + ": " + vendorVerdict);
    }
    return CheckRow.of(ITEM, "jdk", CheckRow.Status.PASS, shown + ": supported");
  }

  /**
   * Null for a verified vendor (Temurin, AdoptOpenJDK, Oracle, Corretto, Zulu), else why it is
   * unverified.
   */
  static String vendorVerdict(String vendor, String vmName) {
    String v = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
    String vm = vmName == null ? "" : vmName.toLowerCase(Locale.ROOT);
    if (vm.contains("openj9") || v.contains("ibm") || v.contains("international business")) {
      return "unverified JVM (IBM Semeru / OpenJ9)";
    }
    if (v.contains("adoptium")
        || v.contains("adoptopenjdk")
        || v.contains("oracle")
        || v.contains("amazon")
        || v.contains("azul")) {
      return null;
    }
    if (v.contains("bellsoft")) {
      return "unverified vendor (Liberica)";
    }
    if (v.contains("microsoft")) {
      return "unverified vendor (Microsoft Build of OpenJDK)";
    }
    return "unverified vendor (verified: Temurin, Oracle, Corretto, Zulu)";
  }

  /**
   * True for Java 9+ and for Java 8 update 252 or later ({@code 1.8.0_252}). Unparseable versions
   * are treated as supported (newer version schemes).
   */
  static boolean jdkSupported(String javaVersion) {
    if (javaVersion == null || !javaVersion.startsWith("1.")) {
      return true;
    }
    if (!javaVersion.startsWith("1.8.0")) {
      return false; // Java 7 and older
    }
    int underscore = javaVersion.indexOf('_');
    if (underscore < 0) {
      return false;
    }
    int end = underscore + 1;
    while (end < javaVersion.length() && Character.isDigit(javaVersion.charAt(end))) {
      end++;
    }
    try {
      return Integer.parseInt(javaVersion.substring(underscore + 1, end)) >= 252;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** The feature release ({@code 1.8.0_x} is 8, {@code 21.0.5} is 21); -1 when unknown. */
  static int feature(String javaVersion) {
    if (javaVersion == null || javaVersion.isEmpty()) {
      return -1;
    }
    String v = javaVersion.startsWith("1.") ? javaVersion.substring(2) : javaVersion;
    int end = 0;
    while (end < v.length() && Character.isDigit(v.charAt(end))) {
      end++;
    }
    if (end == 0) {
      return -1;
    }
    try {
      return Integer.parseInt(v.substring(0, end));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
