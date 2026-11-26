/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.bootstrap;

import io.argos.server.admin.apps.AppAdminService;
import io.argos.server.admin.apps.AppInputs;
import io.argos.server.admin.bootstrap.BootstrapAppsFile.Entry;
import io.argos.server.admin.token.TokenService;
import io.argos.server.admin.token.TokenService.Registration;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.session.AppRef;
import io.argos.server.storage.postgres.app.AppRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Registers the applications and agent tokens of {@code argos.bootstrap.apps-file} ({@code
 * ARGOS_BOOTSTRAP_APPS_FILE}; 17 §3.2, §3.8, §6.4; WO-41) at every start: the Compose demo profile
 * registers {@code order-api} and {@code payment-api} with the tokens {@code argos-env-init.sh
 * --demo} generated, and an unattended installation may do the same for its own applications.
 *
 * <p>A step of the {@code LISTENERS} phase after the first administrator ({@code setup-bootstrap})
 * and before the gRPC listener, so the tokens authenticate from the first Hello. For each entry of
 * the file ({@link BootstrapAppsFile}):
 *
 * <ul>
 *   <li>a missing application is created through {@link AppAdminService#create} by the system actor
 *       (audit {@code APP_CREATED}) with the defaults of the API (Apdex T 500 ms, the environment's
 *       registration policy); its {@code recommended_rules_applied} is false, so the alert engine's
 *       recommended rules job creates the recommended rules (12 §5.6) at its next run;
 *   <li>the token file's first line is registered through {@link TokenService#register}: SHA-256
 *       and prefix only, at most 5 active tokens, audit {@code TOKEN_ISSUED}; a token the
 *       application has already is left alone, so a restart changes nothing.
 * </ul>
 *
 * A file that is missing, unreadable or invalid, a token file that is missing or does not hold a
 * well-formed token, a token another application has, or the token limit stops the start with one
 * message naming the file, the entry and the problem, never a token. Logs show application names
 * and {@code argos_****} only. Without the setting the step does nothing.
 */
@Component
public class AppBootstrapPhase implements StartupPhase, Ordered {

  /** Right after {@code setup-bootstrap} (highest precedence) in {@code LISTENERS}. */
  static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  /** Upper bound of a token file (one token and a line end, with room for blanks). */
  static final int MAX_TOKEN_FILE_BYTES = 1024;

  /** How a token appears in a log line or message. */
  static final String MASKED_TOKEN = "argos_****";

  private static final Logger LOG = LoggerFactory.getLogger(AppBootstrapPhase.class);

  private final ArgosProperties properties;
  private final AppRepository apps;
  private final AppAdminService admin;
  private final TokenService tokens;

  public AppBootstrapPhase(
      ArgosProperties properties, AppRepository apps, AppAdminService admin, TokenService tokens) {
    this.properties = properties;
    this.apps = apps;
    this.admin = admin;
    this.tokens = tokens;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.LISTENERS;
  }

  @Override
  public String name() {
    return "bootstrap-apps";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  /**
   * What happened to one entry.
   *
   * @param name application name
   * @param env environment
   * @param appId its id
   * @param appCreated whether this start created it
   * @param token what happened to its token
   * @param tokenFile the token file
   * @param loosePermissions the token file's POSIX permissions when group or others may read it,
   *     else null
   */
  record Outcome(
      String name,
      String env,
      int appId,
      boolean appCreated,
      Registration token,
      Path tokenFile,
      String loosePermissions) {}

  @Override
  public void run() throws StartupPhaseException {
    String configured = properties.bootstrap().appsFile();
    if (configured == null) {
      return;
    }
    List<Outcome> outcomes = apply(configured);
    for (Outcome o : outcomes) {
      log(o);
    }
  }

  /** Applies the file; no logging (it handles the tokens). */
  List<Outcome> apply(String configured) throws StartupPhaseException {
    Path file = path(configured, "argos.bootstrap.apps-file");
    List<Entry> entries;
    try {
      entries = BootstrapAppsFile.parse(read(file, BootstrapAppsFile.MAX_FILE_BYTES));
    } catch (IOException e) {
      throw new StartupPhaseException(
          "argos.bootstrap.apps-file names a file that cannot be read ("
              + file.toAbsolutePath()
              + "): "
              + e.getClass().getSimpleName(),
          e);
    } catch (IllegalArgumentException e) {
      throw new StartupPhaseException(
          "argos.bootstrap.apps-file ("
              + file.toAbsolutePath()
              + ") is invalid: "
              + e.getMessage());
    }
    List<Outcome> outcomes = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      outcomes.add(register(file, i + 1, entries.get(i)));
    }
    return outcomes;
  }

  private Outcome register(Path appsFile, int number, Entry entry) throws StartupPhaseException {
    String where =
        "argos.bootstrap.apps-file ("
            + appsFile.toAbsolutePath()
            + ") apps["
            + number
            + "] "
            + entry.name()
            + "/"
            + entry.env();
    String name;
    String env;
    try {
      name = AppInputs.name(entry.name());
      env = AppInputs.env(entry.env());
    } catch (ApiException e) {
      throw new StartupPhaseException(where + ": the name or environment is not valid");
    }
    Path tokenFile = path(entry.tokenFile(), where + " token-file");
    String token = readToken(tokenFile, where);
    boolean created = false;
    Optional<AppRef> existing = apps.find(name, env);
    int appId;
    if (existing.isPresent()) {
      appId = existing.get().id();
    } else {
      try {
        appId =
            admin
                .create(
                    new AppAdminService.NewApp(name, env, null, AppInputs.DEFAULT_APDEX_T_MS, null),
                    Actor.system())
                .id();
        created = true;
      } catch (ApiException e) {
        if (e.errorCode() != ErrorCode.ALREADY_EXISTS) {
          throw new StartupPhaseException(where + ": the application cannot be created");
        }
        appId =
            apps.find(name, env)
                .orElseThrow(
                    () -> new StartupPhaseException(where + ": the application cannot be created"))
                .id();
      }
    }
    Registration registration;
    try {
      registration = tokens.register(Actor.system(), appId, token, null);
    } catch (ApiException e) {
      throw new StartupPhaseException(where + ": " + reason(e, tokenFile));
    }
    return new Outcome(
        name,
        env,
        appId,
        created,
        registration,
        tokenFile,
        PrivateFiles.loosePermissions(tokenFile).orElse(null));
  }

  /** The operator-facing reason of a refused registration; never the token. */
  private static String reason(ApiException e, Path tokenFile) {
    List<String> detail = e.details().stream().map(d -> d.reason()).toList();
    if (detail.contains("token_limit")) {
      return "the application has its maximum of "
          + TokenService.MAX_ACTIVE_PER_APP
          + " active agent tokens; revoke one or remove the entry";
    }
    if (detail.contains("registered_elsewhere")) {
      return "the token in "
          + tokenFile.toAbsolutePath()
          + " belongs to another application; generate a new token for this one";
    }
    if (detail.contains("invalid_format")) {
      return "the token file "
          + tokenFile.toAbsolutePath()
          + " does not hold an agent token ("
          + MASKED_TOKEN
          + ": argos_ and 43 base64url characters)";
    }
    return "the token cannot be registered (" + e.errorCode().code() + ")";
  }

  /** The first line of the token file, without blanks; the content never reaches a message. */
  private static String readToken(Path file, String where) throws StartupPhaseException {
    String text;
    try {
      text = read(file, MAX_TOKEN_FILE_BYTES);
    } catch (IOException e) {
      throw new StartupPhaseException(
          where
              + ": the token-file "
              + file.toAbsolutePath()
              + " cannot be read: "
              + e.getClass().getSimpleName());
    }
    int nl = text.indexOf('\n');
    String token = (nl < 0 ? text : text.substring(0, nl)).strip();
    if (token.isEmpty()) {
      throw new StartupPhaseException(
          where + ": the token-file " + file.toAbsolutePath() + " is empty");
    }
    return token;
  }

  private static String read(Path file, int maxBytes) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new java.nio.file.NoSuchFileException("not a regular file");
    }
    String text = PrivateFiles.read(file, maxBytes);
    // A byte order mark (U+FEFF) left by an editor is not part of the file.
    return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
  }

  private static Path path(String value, String key) throws StartupPhaseException {
    try {
      return Paths.get(value.strip());
    } catch (InvalidPathException e) {
      throw new StartupPhaseException(key + " is not a valid path");
    }
  }

  // Logging lives here: it sees names and outcomes, never a token (T-SEC-48b).

  private static void log(Outcome o) {
    if (o.loosePermissions() != null) {
      LOG.warn(
          "The agent token file {} of bootstrap application {}/{} is readable by group or others"
              + " ({}); restrict it to the owner (chmod 600)",
          o.tokenFile().toAbsolutePath(),
          o.name(),
          o.env(),
          o.loosePermissions());
    }
    switch (o.token()) {
      case REGISTERED ->
          LOG.info(
              "Bootstrap application {}/{} (id {}){}: agent token {} registered",
              o.name(),
              o.env(),
              o.appId(),
              o.appCreated() ? " created" : "",
              MASKED_TOKEN);
      case ALREADY_ACTIVE ->
          LOG.info(
              "Bootstrap application {}/{} (id {}){}: agent token {} already registered",
              o.name(),
              o.env(),
              o.appId(),
              o.appCreated() ? " created" : "",
              MASKED_TOKEN);
      case ALREADY_INACTIVE ->
          LOG.warn(
              "Bootstrap application {}/{} (id {}): its agent token {} is revoked or expired and"
                  + " no longer authenticates; issue a new token and update its token file",
              o.name(),
              o.env(),
              o.appId(),
              MASKED_TOKEN);
    }
  }
}
