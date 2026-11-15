/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.password;

import io.argos.server.auth.Sensitive;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.config.ArgosProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The banned passwords of the password policy (16 §7.9, SEC-48): the 10,000 most common passwords
 * (SecLists {@code 10k-most-common.txt}, MIT License, see {@code NOTICE}) shipped as the SHA-256 of
 * each lower-cased entry in {@value #RESOURCE} (made by {@code
 * server/scripts/GeneratePasswordDenylist.java}, never downloaded at build or run time), plus the
 * operator's own list {@code argos.auth.password.banned-list-file} (plain text, one password per
 * line, {@code #} comments). Matching ignores case: the candidate is lower-cased and hashed.
 */
@Component
public class PasswordDenylist {

  private static final Logger LOG = LoggerFactory.getLogger(PasswordDenylist.class);

  /** The bundled list (class path). */
  public static final String RESOURCE = "security/password-denylist.sha256";

  /** Entries of the bundled list (10k-most-common.txt at the pinned commit). */
  public static final int BUNDLED_ENTRIES = 10_001;

  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

  /** Upper bound of the operator's list. */
  static final long MAX_EXTRA_FILE_BYTES = 16L * 1024 * 1024;

  private final Set<String> hashes;
  private final int bundled;

  public PasswordDenylist(ArgosProperties properties) {
    Set<String> set = new HashSet<>(BUNDLED_ENTRIES * 2);
    this.bundled = loadBundled(set);
    String extra = properties.auth().password().bannedListFile();
    if (extra != null) {
      loadExtra(Paths.get(extra.strip()), set);
    }
    this.hashes = Set.copyOf(set);
  }

  /** Whether {@code password} is banned (case-insensitive). */
  public boolean contains(@Sensitive String password) {
    return password != null && hashes.contains(hash(password));
  }

  /** Entries of the bundled list that were loaded. */
  public int bundledEntries() {
    return bundled;
  }

  /** Every banned hash (bundled and operator list). */
  public int size() {
    return hashes.size();
  }

  /** The hash of an entry or candidate: SHA-256 hex of its lower-case UTF-8 bytes. */
  static String hash(String password) {
    return SecureTokens.sha256Hex(password.toLowerCase(Locale.ROOT));
  }

  private static int loadBundled(Set<String> into) {
    InputStream in = PasswordDenylist.class.getClassLoader().getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new IllegalStateException("The password denylist " + RESOURCE + " is missing");
    }
    int count = 0;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        if (!HEX64.matcher(line).matches()) {
          throw new IllegalStateException("The password denylist " + RESOURCE + " is corrupt");
        }
        into.add(line);
        count++;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
    return count;
  }

  private static void loadExtra(Path file, Set<String> into) {
    try {
      if (Files.size(file) > MAX_EXTRA_FILE_BYTES) {
        throw new IllegalStateException(
            "argos.auth.password.banned-list-file is larger than "
                + MAX_EXTRA_FILE_BYTES
                + " bytes");
      }
      int count = 0;
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        into.add(hash(line));
        count++;
      }
      LOG.info("Banned passwords: {} entries from argos.auth.password.banned-list-file", count);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "argos.auth.password.banned-list-file cannot be read (" + file.toAbsolutePath() + ")", e);
    }
  }
}
