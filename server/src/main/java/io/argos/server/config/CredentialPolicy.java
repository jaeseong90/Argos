/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.config.ArgosProperties.Credential;
import io.argos.server.config.SecretValues.SecretException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The database credential policy of SEC-74 (12 §10, 16 §10.6): a PostgreSQL or ClickHouse password
 * that is not set, empty or white space only, shorter than {@value #MIN_LENGTH} characters or
 * (ignoring case) on the forbidden list refuses the start; length and list ignore leading and
 * trailing white space. The forbidden list is the union of 12 §10 ({@code changeme}, {@code argos},
 * {@code password}) and 16 §10.6 ({@code postgres}, {@code clickhouse}, {@code admin}, {@code
 * 123456}, {@code qwerty}, {@code default}).
 *
 * <p>Findings name the setting and the account, never the value.
 */
public final class CredentialPolicy {

  /** Minimum password length (16 §10.6). */
  public static final int MIN_LENGTH = 12;

  /** Forbidden passwords, lower case (12 §10 and 16 §10.6). */
  public static final Set<String> FORBIDDEN =
      Set.of(
          "changeme",
          "argos",
          "password",
          "postgres",
          "clickhouse",
          "admin",
          "123456",
          "qwerty",
          "default");

  private CredentialPolicy() {}

  /** Why a password is rejected. */
  public enum Problem {
    /** Neither the file key nor the value key is set. */
    NOT_SET("is not set"),
    /** The password (or the first line of its file) is empty or white space only. */
    EMPTY("is empty"),
    /** On the forbidden list (ignoring case and leading and trailing white space). */
    FORBIDDEN("is a forbidden default password"),
    /** Shorter than {@link #MIN_LENGTH} without leading and trailing white space. */
    TOO_SHORT("is shorter than " + MIN_LENGTH + " characters");

    private final String text;

    Problem(String text) {
      this.text = text;
    }

    /** Operator-facing text, e.g. {@code is shorter than 12 characters}. */
    public String text() {
      return text;
    }
  }

  /** The problem of a password, if any. */
  public static Optional<Problem> check(Optional<String> password) {
    if (password.isEmpty()) {
      return Optional.of(Problem.NOT_SET);
    }
    String value = password.get();
    if (value.isBlank()) {
      return Optional.of(Problem.EMPTY);
    }
    // Leading and trailing white space only pads the length, so it does not count here; the
    // password that is used stays the value as given.
    String stripped = value.strip();
    if (FORBIDDEN.contains(stripped.toLowerCase(Locale.ROOT))) {
      return Optional.of(Problem.FORBIDDEN);
    }
    if (stripped.codePointCount(0, stripped.length()) < MIN_LENGTH) {
      return Optional.of(Problem.TOO_SHORT);
    }
    return Optional.empty();
  }

  /**
   * One database account to check.
   *
   * @param prefix key prefix, e.g. {@code argos.clickhouse.writer}
   * @param description account description for the message, e.g. {@code ClickHouse writer}
   * @param credential the configured credential
   */
  public record Account(String prefix, String description, Credential credential) {}

  /** The five database accounts of C-11 / ADR-0010 C-97. */
  public static List<Account> databaseAccounts(ArgosProperties properties) {
    return List.of(
        new Account(
            "argos.postgres.owner", "PostgreSQL schema owner", properties.postgres().owner()),
        new Account("argos.postgres", "PostgreSQL runtime account", properties.postgres().app()),
        new Account(
            "argos.clickhouse.writer", "ClickHouse writer", properties.clickhouse().writer()),
        new Account(
            "argos.clickhouse.reader", "ClickHouse reader", properties.clickhouse().reader()),
        new Account(
            "argos.clickhouse.migrator",
            "ClickHouse migrator",
            properties.clickhouse().migrator()));
  }

  /**
   * The findings for every database account, in order; empty when all pass. Each finding names the
   * setting that supplied the password (the file key unless only the value key is set) and the
   * account.
   */
  public static List<String> findings(ArgosProperties properties) {
    List<String> findings = new ArrayList<>();
    for (Account account : databaseAccounts(properties)) {
      Credential c = account.credential();
      String setting =
          c.passwordFile() == null && c.password() != null
              ? account.prefix() + ".password"
              : account.prefix() + ".password-file";
      String who = account.description() + " " + c.user();
      Optional<String> password;
      try {
        password = SecretValues.password(account.prefix(), c);
      } catch (SecretException e) {
        findings.add(e.getMessage() + " (" + who + ")");
        continue;
      }
      check(password).ifPresent(p -> findings.add(setting + " (" + who + ") " + p.text()));
    }
    return findings;
  }
}
