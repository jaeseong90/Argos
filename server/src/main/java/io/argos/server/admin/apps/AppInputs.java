/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.apps;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.session.api.ApdexOverrides;
import io.argos.server.storage.postgres.app.AppAdminRepository;
import io.argos.server.storage.postgres.app.AppRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of the administration inputs of applications (14 §4.3, §7.2): names, environments,
 * groups, the Apdex T, the registration policy and the application packages (ADR-0010 C-207 (3)).
 * Every refusal is 400 with a detail naming the field and a fixed reason; values are never echoed.
 */
public final class AppInputs {

  /** Most entries of {@code app_packages}. */
  public static final int MAX_PACKAGES = 50;

  /** Most characters of one package name. */
  public static final int MAX_PACKAGE_CHARS = 255;

  /** Default Apdex T of a new application (14 §4.3). */
  public static final int DEFAULT_APDEX_T_MS = 500;

  /** A dotted Java package name (ASCII identifiers, the form the agent's frames carry). */
  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

  /**
   * Packages that are never the application's (the JDK and Argos itself; the same roots the agent
   * refuses as custom instrumentation targets).
   */
  private static final List<String> FORBIDDEN_ROOTS =
      List.of("java", "javax", "jdk", "sun", "io.argos");

  private AppInputs() {}

  /** A required name: stripped, 1 to 128 characters, no control characters. */
  public static String name(String value) {
    return required("name", value, AppRepository.MAX_NAME_CHARS);
  }

  /** A required environment: stripped, 1 to 32 characters, no control characters. */
  public static String env(String value) {
    return required("env", value, AppRepository.MAX_ENV_CHARS);
  }

  /** An optional group: null or blank is none; at most 64 characters, no control characters. */
  public static String group(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return required("group", value, AppAdminRepository.MAX_GROUP_CHARS);
  }

  /** An Apdex T: 1 to 65,535 ms. */
  public static int apdexT(String field, int value) {
    if (value < ApdexOverrides.MIN_T_MS || value > ApdexOverrides.MAX_T_MS) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The Apdex T is 1 to 65535 ms")
          .detail(field, "out_of_range");
    }
    return value;
  }

  /** The registration policy: {@code auto} (true) or {@code approval} (false); null when absent. */
  public static Boolean autoRegister(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "auto" -> Boolean.TRUE;
      case "approval" -> Boolean.FALSE;
      default ->
          throw new ApiException(
                  ErrorCode.INVALID_REQUEST, "registration_policy is auto or approval")
              .detail("registration_policy", "unsupported_value");
    };
  }

  /**
   * The application packages: at most {@value #MAX_PACKAGES} dotted Java package names of at most
   * {@value #MAX_PACKAGE_CHARS} characters, none in or below {@code java}, {@code javax}, {@code
   * jdk}, {@code sun} or {@code io.argos}; duplicates are dropped, the order is kept.
   */
  public static List<String> packages(List<String> values) {
    if (values == null) {
      return null;
    }
    if (values.size() > MAX_PACKAGES) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "At most 50 application packages")
          .detail("app_packages", "too_many");
    }
    Set<String> out = new LinkedHashSet<>();
    for (String raw : values) {
      String value = raw == null ? "" : raw.strip();
      if (value.isEmpty() || value.length() > MAX_PACKAGE_CHARS) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "A package name is 1 to 255 characters")
            .detail("app_packages", value.isEmpty() ? "required" : "too_long");
      }
      if (!PACKAGE.matcher(value).matches()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "A package name is a Java package name")
            .detail("app_packages", "invalid");
      }
      for (String root : FORBIDDEN_ROOTS) {
        if (value.equals(root) || value.startsWith(root + ".")) {
          throw new ApiException(
                  ErrorCode.INVALID_REQUEST, "JDK and Argos packages are not application packages")
              .detail("app_packages", "forbidden_package");
        }
      }
      out.add(value);
    }
    return new ArrayList<>(out);
  }

  private static String required(String field, String value, int maxChars) {
    String v = value == null ? "" : value.strip();
    if (v.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail(field, "required");
    }
    if (v.length() > maxChars) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail(field, "too_long");
    }
    for (int i = 0; i < v.length(); i++) {
      if (Character.isISOControl(v.charAt(i))) {
        throw new ApiException(ErrorCode.INVALID_REQUEST).detail(field, "control_character");
      }
    }
    return v;
  }
}
