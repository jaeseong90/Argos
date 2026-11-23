/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Masks configuration values that may be secrets before they are shown or audited (14 §4.3
 * effective-config "토큰·비밀값은 ****", 16 §5.7 CONFIG_PUSH "비밀값은 저장 전 마스킹"). The remote allow list
 * never covers secret keys (16 §5.5: {@code server.*} is immutable), so this is a last line: a
 * value is replaced by {@value #MASK} when its key names a secret, an agent token inside a value
 * becomes {@code argos_****} and a JSON Web Token {@value #MASK}.
 */
public final class ConfigValueMasking {

  /** The replacement of a masked value. */
  public static final String MASK = "****";

  private static final Pattern SECRET_KEY =
      Pattern.compile(
          "(?i).*(pass|pwd|secret|token|credential|private|apikey|api[-_]key|bearer|jwt"
              + "|signature|cookie|salt).*");
  private static final Pattern TOKEN = Pattern.compile("argos_[A-Za-z0-9_-]{20,}");
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

  private ConfigValueMasking() {}

  /** The shown form of {@code value} of {@code key}; null stays null. */
  public static @Nullable String mask(String key, @Nullable String value) {
    if (value == null) {
      return null;
    }
    if (SECRET_KEY.matcher(key).matches()) {
      return MASK;
    }
    String out = TOKEN.matcher(value).replaceAll("argos_****");
    return JWT.matcher(out).replaceAll(MASK);
  }
}
