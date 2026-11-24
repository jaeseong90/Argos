/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import io.argos.agent.core.config.EffectiveConfig.Source;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * P4 of the premain sequence (10 §4.3, §15): reads the configuration and returns the typed
 * snapshot, the effective values with their sources and the token.
 *
 * <p>Contract of {@link #load(ConfigInputs)}:
 *
 * <ol>
 *   <li>Sources, a later one wins (10 §15, 02 §7): documented default → {@code argos-agent.yml}
 *       ({@link ConfigInputs#yamlFile()}, parsed by the own bounded {@link YamlSubset}) → {@code
 *       -Dargos.<key>} → {@code ARGOS_<KEY>} ({@link ConfigKeys#environmentName(String)}). Nested
 *       yml maps are flattened to dot keys; sequences become YAML flow lists so that {@link
 *       KeySpec} parses items that contain commas; a sequence of mappings is accepted for {@code
 *       instrumentation.custom-methods} only ({@code pattern}, {@code as-transaction}; an item with
 *       the {@code step-type} field of the earlier format becomes an item with a step type suffix,
 *       which {@code CustomMethodPolicy} ignores with a WARN, ADR-0010 C-237 (5)). {@code
 *       sampling.profile.slow-threshold-ms.default} is the base key, {@code ...services.<name>} the
 *       per-service family. A yml file outside the subset or over a cap gives one WARN with the
 *       line number and is ignored as a whole (no partial application).
 *   <li>Unknown keys and unknown {@code ARGOS_*} variables produce one warning each (02 §7), never
 *       a failure (O-R03). {@code argos.config} / {@code ARGOS_CONFIG} are inputs, not unknown
 *       keys. {@code agent.disabled} is honoured from {@code -Dargos.agent.disabled} only.
 *   <li>Release builds ({@link ConfigInputs#releaseBuild()}, C-89) drop every {@code test.*} and
 *       {@code testing.*} key from every source ({@code -Dargos.test.*}, {@code ARGOS_TEST_*},
 *       {@code ARGOS_TESTING_*}, the yml) with one WARN naming them (T-BLD-010); {@code debug.*}
 *       keys stay.
 *   <li>Token, three ways (SEC-06, 16 §2.6): {@code server.token} > {@code server.token-file}
 *       ({@link TokenFile}: read once, trimmed, at most 4 KiB, WARN once when group or others can
 *       read it) > {@code ARGOS_TOKEN}; a later way is used when an earlier one gives no token. A
 *       plaintext {@code server.token} in the yml gives the WARN {@value #PLAINTEXT_TOKEN_WARNING}
 *       once; the yml value {@value #TOKEN_PLACEHOLDER} is replaced by the variable. Placeholders
 *       {@code ${...}} anywhere else are not supported: WARN and the key stays unset. The token
 *       goes to {@link LoadedConfig#token()} only; warnings, {@link EffectiveConfig} and {@link
 *       ConfigHash} show {@value TokenHolder#MASK} or nothing. A value quoted in a warning is
 *       masked before it is cut to 64 characters, so no part of a token shows either.
 *   <li>Validation through {@link KeySpec}: an invalid value gives a WARN naming key and source,
 *       and the key keeps its documented default (F-A15). An empty scalar means unset; an empty
 *       list is a value. A pattern of {@code capture.mask-patterns} or {@code
 *       commands.env-dump.mask-patterns} that does not compile, or is over the item cap, is skipped
 *       with a WARN and the others stay (one bad pattern never removes the good ones).
 *   <li>Required keys (10 §4.3 P4): a missing {@code server.address} is one ERROR line and no
 *       transport ({@link LoadedConfig#transportConfigured()}) unless a development build selects a
 *       {@code test.exporter} other than {@code grpc}; a missing {@code app.name} is one ERROR line
 *       and the name {@link #fallbackAppName(String)}; a missing {@code app.env} is {@code default}
 *       with a WARN. The application and the instrumentation keep running.
 *   <li>Never throws and never writes to stdout/stderr: an unreadable file is a warning and the
 *       other sources still apply. Warnings are capped at {@value #MAX_WARNINGS}. Time budget 30 ms
 *       (10 §4.3 P4).
 * </ol>
 */
public final class ConfigLoader {
  /** Upper bound of {@link LoadedConfig#warnings()}, the last line counting the ones left out. */
  public static final int MAX_WARNINGS = 100;

  /** The WARN of 16 §2.6 for a plaintext {@code server.token} in the yml, logged once. */
  public static final String PLAINTEXT_TOKEN_WARNING =
      "token is stored in plaintext in argos-agent.yml; prefer token-file or ARGOS_TOKEN";

  /** The WARN for a plaintext {@code -Dargos.server.token} (visible in the process list). */
  public static final String PLAINTEXT_PROPERTY_TOKEN_WARNING =
      "token is given in plaintext as -Dargos.server.token (visible in the process list); prefer"
          + " token-file or ARGOS_TOKEN";

  /** The only placeholder of the yml (16 §2.6), as the value of {@code server.token}. */
  public static final String TOKEN_PLACEHOLDER = "${ARGOS_TOKEN}";

  /** Application name when neither {@code app.name} nor the command line gives one. */
  public static final String FALLBACK_APP_NAME = "unnamed-app";

  /** Longest fallback application name. */
  static final int MAX_FALLBACK_NAME_CHARS = 64;

  /** The yml form of the base slow threshold ({@code slow-threshold-ms: {default: ...}}). */
  static final String SLOW_THRESHOLD_DEFAULT = ConfigKeys.SLOW_THRESHOLD_MS + ".default";

  /** Fields of one {@code instrumentation.custom-methods} mapping (10 §6.10). */
  static final String CUSTOM_PATTERN = "pattern";

  /** The removed field of the earlier format: the item is ignored (ADR-0010 C-237 (5)). */
  static final String CUSTOM_STEP_TYPE = "step-type";

  static final String CUSTOM_AS_TRANSACTION = "as-transaction";

  /** Ignored test settings named in the T-BLD-010 WARN; the rest are counted. */
  static final int MAX_LISTED_NAMES = 20;

  /** Per-service slow threshold settings collected over all sources (AgentConfig keeps 1,000). */
  static final int MAX_FAMILY_SETTINGS = AgentConfig.MAX_SERVICE_THRESHOLDS;

  private static final int MAX_QUOTED_CHARS = 64;

  private ConfigLoader() {}

  /** Loads the configuration of {@code inputs} (see the class comment). */
  public static LoadedConfig load(ConfigInputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    return load(inputs, TokenFile.POSIX);
  }

  /** {@link #load(ConfigInputs)} with a token file permission probe (tests). */
  static LoadedConfig load(ConfigInputs inputs, TokenFile.PermissionProbe probe) {
    try {
      return new Run(inputs, probe).load();
    } catch (RuntimeException e) {
      // F-A15: a bug here must not stop the application; the defaults keep the agent harmless.
      AgentConfig defaults = AgentConfig.defaults();
      return new LoadedConfig(
          defaults,
          defaultsOnly(),
          TokenHolder.NONE,
          Collections.singletonList(
              "configuration could not be loaded ("
                  + e.getClass().getName()
                  + "); using the defaults"));
    }
  }

  /**
   * The application name used when {@code app.name} is missing: the jar or war file name of the
   * command line without its version ({@code shop-boot3-1.2.0.jar} → {@code shop-boot3}), else the
   * simple name of the main class ({@code com.example.OrderApplication} → {@code
   * OrderApplication}); characters outside {@code [A-Za-z0-9._-]} become {@code -}, at most {@value
   * #MAX_FALLBACK_NAME_CHARS} characters; {@value #FALLBACK_APP_NAME} when nothing is left. Only
   * the first word of the command line is read, never the program arguments.
   */
  public static String fallbackAppName(String javaCommand) {
    if (javaCommand == null) {
      return FALLBACK_APP_NAME;
    }
    String first = javaCommand.trim();
    int space = first.indexOf(' ');
    if (space > 0) {
      first = first.substring(0, space);
    }
    String lower = first.toLowerCase(Locale.ROOT);
    String name;
    if (lower.endsWith(".jar") || lower.endsWith(".war")) {
      int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
      name = first.substring(slash + 1, first.length() - 4);
      for (int i = 1; i + 1 < name.length(); i++) {
        if (name.charAt(i) == '-' && Character.isDigit(name.charAt(i + 1))) {
          name = name.substring(0, i); // shop-boot3-1.2.0 -> shop-boot3
          break;
        }
      }
    } else {
      name = first.substring(first.lastIndexOf('.') + 1);
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length() && out.length() < MAX_FALLBACK_NAME_CHARS; i++) {
      char c = name.charAt(i);
      boolean plain =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      out.append(plain ? c : '-');
    }
    String result = out.toString();
    while (result.startsWith("-") || result.startsWith(".")) {
      result = result.substring(1);
    }
    return result.isEmpty() ? FALLBACK_APP_NAME : result;
  }

  private static EffectiveConfig defaultsOnly() {
    Map<String, EffectiveConfig.Entry> entries = new LinkedHashMap<String, EffectiveConfig.Entry>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (!spec.isFamily()) {
        entries.put(
            spec.name(),
            new EffectiveConfig.Entry(
                spec.isSecret() ? null : spec.defaultValue(), Source.DEFAULT));
      }
    }
    return new EffectiveConfig(entries);
  }

  /** The environment variable of every registry key ({@code ARGOS_LOG_LEVEL} → log.level). */
  private static final class EnvironmentIndex {
    static final Map<String, String> NAMES = build();

    private static Map<String, String> build() {
      Map<String, String> names = new LinkedHashMap<String, String>();
      for (KeySpec spec : ConfigKeys.all()) {
        if (!spec.isFamily()) {
          String variable = ConfigKeys.environmentName(spec.name());
          if (!names.containsKey(variable)) {
            names.put(variable, spec.name());
          }
        }
      }
      return Collections.unmodifiableMap(names);
    }
  }

  /** The variable of each key (tests: no two keys share one). */
  static Map<String, String> environmentIndex() {
    return EnvironmentIndex.NAMES;
  }

  /** One raw value and where it came from. */
  private static final class Setting {
    final String raw;
    final Source source;
    final String origin;
    final boolean placeholder;

    Setting(String raw, Source source, String origin, boolean placeholder) {
      this.raw = raw;
      this.source = source;
      this.origin = origin;
      this.placeholder = placeholder;
    }

    Setting withRaw(String value) {
      return new Setting(value, source, origin, placeholder);
    }
  }

  /** One load: collects settings source by source, then resolves, validates and reports. */
  private static final class Run {
    private final ConfigInputs inputs;
    private final TokenFile.PermissionProbe probe;
    private final Map<String, Setting> settings = new LinkedHashMap<String, Setting>();
    private final Map<String, Setting> accepted = new LinkedHashMap<String, Setting>();
    private final Set<String> warnings = new LinkedHashSet<String>();
    private final List<String> errors = new ArrayList<String>();
    private final List<String> notes = new ArrayList<String>();
    private final List<String> ignoredTestSettings = new ArrayList<String>();
    private int ignoredTestSettingsNotListed;
    private int familySettings;
    private int suppressedWarnings;
    private String environmentToken;
    private Source tokenSource = Source.DEFAULT;
    private String fallbackName;

    /** The configured token once resolved, for {@link #quote(String)}; null before or without. */
    private String secret;

    Run(ConfigInputs inputs, TokenFile.PermissionProbe probe) {
      this.inputs = inputs;
      this.probe = probe;
    }

    LoadedConfig load() {
      for (String warning : inputs.inputWarnings()) {
        warn(warning);
      }
      readYaml();
      readSystemProperties();
      readEnvironment();
      reportIgnoredTestSettings();
      TokenHolder token = resolveToken();
      secret = token.reveal(); // quote() masks it in the values that validate() quotes
      sanitizeMaskPatterns(ConfigKeys.CAPTURE_MASK_PATTERNS);
      sanitizeMaskPatterns(ConfigKeys.ENV_DUMP_MASK_PATTERNS);
      Map<String, String> resolved = validate();
      checkRequired(resolved);
      AgentConfig config = AgentConfig.of(resolved);
      for (String warning : config.warnings()) {
        warn(warning);
      }
      EffectiveConfig effective = effective(config, token);
      List<String> warningList = new ArrayList<String>(warnings);
      if (suppressedWarnings > 0) {
        warningList.add(suppressedWarnings + " more configuration warnings were not logged");
      }
      return new LoadedConfig(
          config,
          effective,
          token,
          scrub(warningList, token),
          scrub(errors, token),
          scrub(notes, token));
    }

    // --- sources --------------------------------------------------------------------------------

    private void readYaml() {
      File file = inputs.yamlFile();
      if (file == null) {
        return;
      }
      String name = file.getName();
      byte[] bytes;
      try {
        if (!file.isFile()) {
          warn(
              "configuration file "
                  + file.getPath()
                  + " does not exist or is not a file; continuing without it");
          return;
        }
        bytes = readBounded(file);
      } catch (IOException | RuntimeException e) {
        warn(
            "configuration file "
                + file.getPath()
                + " cannot be read ("
                + e.getClass().getSimpleName()
                + "); continuing without it");
        return;
      }
      if (bytes == null) {
        warn(
            "configuration file "
                + file.getPath()
                + " is larger than "
                + YamlSubset.MAX_FILE_BYTES
                + " bytes; the whole file is ignored");
        return;
      }
      List<YamlSubset.Entry> entries;
      try {
        entries = YamlSubset.parse(bytes);
      } catch (YamlSubset.YamlException e) {
        Arrays.fill(bytes, (byte) 0); // the file may hold a plaintext token
        warn(
            "configuration file "
                + file.getPath()
                + (e.line() > 0 ? " line " + e.line() : "")
                + ": "
                + e.getMessage()
                + "; the whole file is ignored");
        return;
      }
      Arrays.fill(bytes, (byte) 0);
      for (YamlSubset.Entry entry : entries) {
        fileEntry(entry, name + " line " + entry.line());
      }
    }

    private void fileEntry(YamlSubset.Entry entry, String origin) {
      String key = normalize(ConfigKeys.canonical(entry.key()));
      KeySpec spec = ConfigKeys.spec(key);
      if (entry.kind() == YamlSubset.Kind.SCALAR) {
        accept(key, entry.scalar() == null ? "" : entry.scalar(), Source.FILE, origin);
      } else if (entry.kind() == YamlSubset.Kind.LIST) {
        if (spec != null && spec.type() != KeySpec.Type.LIST) {
          warn(key + " (" + origin + ") takes a single value, not a list; ignored");
          return;
        }
        accept(key, flow(entry.items()), Source.FILE, origin);
      } else if (spec != null && !key.equals(ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS)) {
        warn(key + " (" + origin + ") does not take a list of mappings; ignored");
      } else {
        accept(key, spec == null ? "" : customMethods(entry.maps(), origin), Source.FILE, origin);
      }
    }

    /**
     * {@code - pattern: ... as-transaction: ...} items as list item strings {@code
     * pattern[|as-transaction]}. An item with a {@code step-type} field keeps it as a step type
     * suffix ({@code pattern|STEP[|as-transaction]}), so that the policy ignores that item alone
     * with its WARN (ADR-0010 C-237 (5)).
     */
    private String customMethods(List<Map<String, String>> maps, String origin) {
      List<String> items = new ArrayList<String>(maps.size());
      for (int i = 0; i < maps.size(); i++) {
        Map<String, String> fields = maps.get(i);
        String where = ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS + " item " + (i + 1);
        for (String field : fields.keySet()) {
          if (!field.equals(CUSTOM_PATTERN)
              && !field.equals(CUSTOM_STEP_TYPE)
              && !field.equals(CUSTOM_AS_TRANSACTION)) {
            warn(
                where
                    + " ("
                    + origin
                    + "): unknown field '"
                    + YamlSubset.printable(field)
                    + "' ignored");
          }
        }
        String pattern = trimmed(fields.get(CUSTOM_PATTERN));
        if (pattern == null) {
          warn(where + " (" + origin + ") has no pattern; item ignored");
          continue;
        }
        String stepType = trimmed(fields.get(CUSTOM_STEP_TYPE));
        String asTransaction = trimmed(fields.get(CUSTOM_AS_TRANSACTION));
        StringBuilder item = new StringBuilder(pattern);
        if (stepType != null) {
          item.append('|').append(stepType.toUpperCase(Locale.ROOT));
        }
        if (asTransaction != null) {
          item.append('|').append(asTransaction.toLowerCase(Locale.ROOT));
        }
        items.add(item.toString());
      }
      return flow(items);
    }

    private void readSystemProperties() {
      for (Map.Entry<String, String> entry :
          new TreeMap<String, String>(inputs.systemProperties()).entrySet()) {
        String key = entry.getKey().substring(ConfigKeys.PROPERTY_PREFIX.length());
        if (key.equals(ConfigKeys.CONFIG_FILE)) {
          continue; // the yml location, read by ConfigInputs
        }
        accept(normalize(key), entry.getValue(), Source.SYSTEM_PROPERTY, "-D" + entry.getKey());
      }
    }

    private void readEnvironment() {
      for (Map.Entry<String, String> entry :
          new TreeMap<String, String>(inputs.environment()).entrySet()) {
        String name = entry.getKey();
        if (name.equals(ConfigKeys.TOKEN_ENVIRONMENT)) {
          environmentToken = entry.getValue();
          continue;
        }
        if (name.equals(ConfigInputs.CONFIG_ENVIRONMENT)) {
          continue;
        }
        String key = EnvironmentIndex.NAMES.get(name);
        if (key == null) {
          if (inputs.releaseBuild()
              && (name.startsWith(ConfigKeys.ENVIRONMENT_PREFIX + "TEST_")
                  || name.startsWith(ConfigKeys.ENVIRONMENT_PREFIX + "TESTING_"))) {
            ignoreTestSetting(name);
          } else {
            warn("unknown environment variable " + quote(name) + " ignored");
          }
          continue;
        }
        accept(key, entry.getValue(), Source.ENVIRONMENT, name);
      }
    }

    /** Records {@code key = raw} from {@code source}; a later source replaces an earlier one. */
    private void accept(String key, String raw, Source source, String origin) {
      if (ConfigKeys.isTestOnly(key) && inputs.releaseBuild()) {
        ignoreTestSetting(source == Source.FILE ? key + " (" + origin + ")" : origin);
        return;
      }
      KeySpec spec = ConfigKeys.spec(key);
      if (spec == null) {
        warn("unknown configuration key '" + quote(key) + "' (" + origin + ") ignored");
        return;
      }
      if (spec.isFamily() && !settings.containsKey(key) && ++familySettings > MAX_FAMILY_SETTINGS) {
        warn(
            "more than "
                + AgentConfig.MAX_SERVICE_THRESHOLDS
                + " per-service slow thresholds; "
                + quote(key)
                + " ("
                + origin
                + ") ignored");
        return;
      }
      if (spec.isPropertyOnly() && source != Source.SYSTEM_PROPERTY) {
        warn(
            key
                + " is read only from -D"
                + ConfigKeys.PROPERTY_PREFIX
                + key
                + "; the value from "
                + origin
                + " is ignored");
        return;
      }
      if (isPlaceholder(raw)) {
        if (spec.isSecret() && source == Source.FILE && raw.trim().equals(TOKEN_PLACEHOLDER)) {
          settings.put(key, new Setting(raw, source, origin, true));
          return;
        }
        warn(
            "placeholders are supported only in argos-agent.yml as server.token: "
                + TOKEN_PLACEHOLDER
                + "; "
                + key
                + " ("
                + origin
                + ") is ignored");
        return;
      }
      settings.put(key, new Setting(raw, source, origin, false));
    }

    private void ignoreTestSetting(String name) {
      if (ignoredTestSettings.size() < MAX_LISTED_NAMES) {
        ignoredTestSettings.add(name);
      } else {
        ignoredTestSettingsNotListed++;
      }
    }

    private void reportIgnoredTestSettings() {
      if (ignoredTestSettings.isEmpty()) {
        return;
      }
      Collections.sort(ignoredTestSettings);
      String more =
          ignoredTestSettingsNotListed > 0 ? " and " + ignoredTestSettingsNotListed + " more" : "";
      warn(
          "test-only settings ignored in a release build (T-BLD-010): "
              + ignoredTestSettings
              + more);
    }

    // --- token ----------------------------------------------------------------------------------

    /** 16 §2.6: {@code server.token} > {@code server.token-file} > {@code ARGOS_TOKEN}. */
    private TokenHolder resolveToken() {
      Setting plain = settings.remove(ConfigKeys.SERVER_TOKEN);
      List<String> used = new ArrayList<String>();
      TokenHolder token = TokenHolder.NONE;
      if (plain != null && plain.placeholder) {
        if (environmentToken != null && !environmentToken.trim().isEmpty()) {
          token = TokenHolder.of(environmentToken, TokenHolder.Source.ENVIRONMENT);
          tokenSource = Source.ENVIRONMENT;
          environmentToken = null; // consumed by the placeholder
        } else {
          warn(
              "server.token ("
                  + plain.origin
                  + ") refers to "
                  + TOKEN_PLACEHOLDER
                  + " but ARGOS_TOKEN is not set; no token from it");
        }
      } else if (plain != null && !plain.raw.trim().isEmpty()) {
        String reason = ConfigKeys.spec(ConfigKeys.SERVER_TOKEN).check(plain.raw);
        if (reason != null) {
          warn("invalid value (hidden) for server.token (" + plain.origin + "): " + reason);
        } else {
          boolean environment = plain.source == Source.ENVIRONMENT;
          token =
              TokenHolder.of(
                  plain.raw,
                  environment ? TokenHolder.Source.ENVIRONMENT : TokenHolder.Source.PLAIN);
          tokenSource = plain.source;
          if (plain.source == Source.FILE) {
            warn(PLAINTEXT_TOKEN_WARNING);
          } else if (plain.source == Source.SYSTEM_PROPERTY) {
            warn(PLAINTEXT_PROPERTY_TOKEN_WARNING);
          }
        }
      }
      Setting file = settings.get(ConfigKeys.SERVER_TOKEN_FILE);
      boolean fileUsable =
          file != null
              && !file.raw.trim().isEmpty()
              && ConfigKeys.spec(ConfigKeys.SERVER_TOKEN_FILE).check(file.raw) == null;
      if (fileUsable) {
        if (token.isPresent()) {
          used.add("server.token-file");
        } else {
          List<String> fileWarnings = new ArrayList<String>();
          String value = TokenFile.read(new File(file.raw.trim()), probe, fileWarnings, notes);
          for (String warning : fileWarnings) {
            warn(warning);
          }
          if (value != null) {
            token = TokenHolder.of(value, TokenHolder.Source.FILE);
            tokenSource = file.source;
          }
        }
      }
      if (environmentToken != null && !environmentToken.trim().isEmpty()) {
        if (token.isPresent()) {
          used.add(ConfigKeys.TOKEN_ENVIRONMENT);
        } else {
          token = TokenHolder.of(environmentToken, TokenHolder.Source.ENVIRONMENT);
          tokenSource = Source.ENVIRONMENT;
        }
      }
      if (!used.isEmpty()) {
        notes.add(
            "agent token taken from "
                + describe(token.source())
                + "; also configured and not used: "
                + used
                + " (16 §2.6 order: server.token, server.token-file, ARGOS_TOKEN)");
      }
      return token;
    }

    private static String describe(TokenHolder.Source source) {
      switch (source) {
        case FILE:
          return "server.token-file";
        case ENVIRONMENT:
          return "the environment";
        default:
          return "server.token";
      }
    }

    // --- validation -----------------------------------------------------------------------------

    /**
     * Masking pattern lists keep their valid patterns: a pattern that does not compile or is too
     * long is skipped with a WARN, patterns over the count cap are dropped with one WARN.
     */
    private void sanitizeMaskPatterns(String key) {
      Setting setting = settings.get(key);
      if (setting == null || setting.raw.length() > KeySpec.MAX_RAW_LENGTH) {
        return; // unset, or rejected as a whole by validate()
      }
      KeySpec spec = ConfigKeys.spec(key);
      List<String> items = KeySpec.splitList(setting.raw, KeySpec.MAX_RAW_LENGTH);
      if (items == null) {
        return; // malformed list: validate() reports it
      }
      List<String> kept = new ArrayList<String>();
      int dropped = 0;
      for (int i = 0; i < items.size(); i++) {
        String item = items.get(i);
        String where = key + " pattern " + (i + 1) + " (" + setting.origin + ")";
        if (kept.size() >= spec.maxItems()) {
          dropped++;
        } else if (item.length() > spec.itemMaxLength()) {
          warn(where + " is longer than " + spec.itemMaxLength() + " characters; skipped");
        } else if (KeySpec.checkFormat(KeySpec.Format.REGEX, item) != null) {
          warn(where + " is not a valid regular expression; skipped");
        } else {
          kept.add(item);
        }
      }
      if (dropped > 0) {
        warn(
            key
                + " ("
                + setting.origin
                + ") has more than "
                + spec.maxItems()
                + " patterns; the first "
                + spec.maxItems()
                + " are used");
      }
      if (kept.size() != items.size()) {
        settings.put(key, setting.withRaw(flow(kept)));
      }
    }

    /** The values that pass {@link KeySpec}; invalid ones are reported and left at the default. */
    private Map<String, String> validate() {
      Map<String, String> resolved = new LinkedHashMap<String, String>();
      for (Map.Entry<String, Setting> entry : settings.entrySet()) {
        String key = entry.getKey();
        Setting setting = entry.getValue();
        KeySpec spec = ConfigKeys.spec(key);
        if (setting.raw.trim().isEmpty() && spec.type() != KeySpec.Type.LIST) {
          continue; // an empty scalar ("key:" or -Dargos.key=) means unset
        }
        String reason = spec.check(setting.raw);
        if (reason != null) {
          String fallback =
              spec.isFamily() || spec.defaultValue() == null
                  ? "it stays unset"
                  : "using the default";
          warn(
              "invalid value '"
                  + quote(setting.raw)
                  + "' for "
                  + key
                  + " ("
                  + setting.origin
                  + "): "
                  + reason
                  + "; "
                  + fallback);
          continue;
        }
        resolved.put(key, setting.raw);
        accepted.put(key, setting);
      }
      return resolved;
    }

    private void checkRequired(Map<String, String> resolved) {
      if (!resolved.containsKey(ConfigKeys.SERVER_ADDRESS) && !testExporterSelected(resolved)) {
        errors.add(
            "server.address is not set: the agent sends no data and opens no connection; set"
                + " server.address in argos-agent.yml, -Dargos.server.address or"
                + " ARGOS_SERVER_ADDRESS");
      }
      if (!resolved.containsKey(ConfigKeys.APP_NAME)) {
        fallbackName = fallbackAppName(inputs.javaCommand());
        resolved.put(ConfigKeys.APP_NAME, fallbackName);
        errors.add(
            "app.name is not set; using '"
                + fallbackName
                + "' from the command line; set app.name in argos-agent.yml, -Dargos.app.name or"
                + " ARGOS_APP_NAME");
      }
      if (!resolved.containsKey(ConfigKeys.APP_ENV)) {
        warn("app.env is not set; using 'default'");
      }
    }

    /** A development build with {@code test.exporter} other than {@code grpc} needs no address. */
    private boolean testExporterSelected(Map<String, String> resolved) {
      String exporter = resolved.get(ConfigKeys.TEST_EXPORTER);
      if (inputs.releaseBuild() || exporter == null) {
        return false;
      }
      int colon = exporter.indexOf(':');
      String name = (colon < 0 ? exporter : exporter.substring(0, colon)).trim();
      return !name.isEmpty() && !name.equalsIgnoreCase("grpc");
    }

    // --- result ---------------------------------------------------------------------------------

    private EffectiveConfig effective(AgentConfig config, TokenHolder token) {
      Map<String, EffectiveConfig.Entry> entries =
          new LinkedHashMap<String, EffectiveConfig.Entry>();
      for (KeySpec spec : ConfigKeys.all()) {
        if (spec.isFamily()) {
          continue;
        }
        String key = spec.name();
        Setting setting = accepted.get(key);
        EffectiveConfig.Entry entry;
        if (spec.isSecret()) {
          entry =
              token.isPresent()
                  ? new EffectiveConfig.Entry(TokenHolder.MASK, tokenSource)
                  : new EffectiveConfig.Entry(null, Source.DEFAULT);
        } else if (setting != null) {
          entry = new EffectiveConfig.Entry(scrub(setting.raw.trim(), token), setting.source);
        } else if (key.equals(ConfigKeys.APP_NAME) && fallbackName != null) {
          entry = new EffectiveConfig.Entry(fallbackName, Source.DEFAULT);
        } else {
          entry = new EffectiveConfig.Entry(spec.defaultValue(), Source.DEFAULT);
        }
        entries.put(key, entry);
      }
      for (Map.Entry<String, Setting> entry : accepted.entrySet()) {
        String key = entry.getKey();
        if (key.startsWith(ConfigKeys.SLOW_THRESHOLD_SERVICES)
            && config
                .slowThresholdServices()
                .containsKey(key.substring(ConfigKeys.SLOW_THRESHOLD_SERVICES.length()))) {
          entries.put(
              key,
              new EffectiveConfig.Entry(
                  scrub(entry.getValue().raw.trim(), token), entry.getValue().source));
        }
      }
      return new EffectiveConfig(entries);
    }

    /**
     * {@code text} with the configured token itself and every 16 §2.1 token shown as {@value
     * TokenHolder#MASK}: an operator who put the token into another key does not see it printed.
     * The configured token is replaced first: a token that is longer than the 16 §2.1 format would
     * otherwise be changed by {@link TokenHolder#maskTokens(String)} and no longer be found whole.
     */
    private static String scrub(String text, TokenHolder token) {
      String out = text;
      String secret = token.reveal();
      if (secret != null && secret.length() >= 4 && out.contains(secret)) {
        out = out.replace(secret, TokenHolder.MASK);
      }
      return TokenHolder.maskTokens(out);
    }

    // --- helpers --------------------------------------------------------------------------------

    private void warn(String warning) {
      if (warnings.contains(warning)) {
        return;
      }
      if (warnings.size() < MAX_WARNINGS - 1) {
        warnings.add(warning);
      } else {
        suppressedWarnings++;
      }
    }

    /**
     * A value or name for a message, at most 64 characters: masked before it is cut ({@link
     * TokenHolder#quoteValue(String, int, String...)}), so neither a 16 §2.1 token nor the
     * configured token shows in part when the value is longer than the cut (SEC-06, C-94). The
     * configured token is known once {@link #resolveToken()} ran (it runs before {@link
     * #validate()}, the step that quotes values); names quoted while the sources are read are
     * masked by the {@code argos_} rule, and {@link #scrub(List, TokenHolder)} replaces the
     * configured token in every complete message at the end.
     */
    private String quote(String value) {
      return TokenHolder.quoteValue(value, MAX_QUOTED_CHARS, secret);
    }

    /** Defence in depth (SEC-06): no message carries the token, whatever key it was put in. */
    private static List<String> scrub(List<String> lines, TokenHolder token) {
      List<String> out = new ArrayList<String>(lines.size());
      for (String line : lines) {
        out.add(scrub(line, token));
      }
      return out;
    }
  }

  /** The base slow threshold written as {@code ...slow-threshold-ms.default}. */
  private static String normalize(String key) {
    return SLOW_THRESHOLD_DEFAULT.equals(key) ? ConfigKeys.SLOW_THRESHOLD_MS : key;
  }

  /** A value that holds {@code ${...}} anywhere (an unresolved placeholder). */
  private static boolean isPlaceholder(String raw) {
    int open = raw.indexOf("${");
    return open >= 0 && raw.indexOf('}', open + 2) > 0;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  /**
   * {@code items} as a YAML flow sequence that {@link KeySpec#splitList(String, int)} reads back
   * item by item: every item double-quoted, {@code \} and {@code "} escaped; null items left out.
   */
  static String flow(List<String> items) {
    StringBuilder out = new StringBuilder("[");
    boolean first = true;
    for (String item : items) {
      if (item == null) {
        continue;
      }
      out.append(first ? "" : ", ").append('"');
      first = false;
      for (int i = 0; i < item.length(); i++) {
        char c = item.charAt(i);
        if (c == '\\' || c == '"') {
          out.append('\\');
        }
        out.append(c);
      }
      out.append('"');
    }
    return out.append(']').toString();
  }

  /** At most {@link YamlSubset#MAX_FILE_BYTES} bytes of {@code file}, null when it holds more. */
  private static byte[] readBounded(File file) throws IOException {
    byte[] buffer = new byte[YamlSubset.MAX_FILE_BYTES + 1];
    int total = 0;
    // java.io, not NIO: FileInputStream is loaded at JVM start, the NIO channels are not (C-79).
    try (InputStream in = new FileInputStream(file)) {
      while (total < buffer.length) {
        int read = in.read(buffer, total, buffer.length - total);
        if (read < 0) {
          break;
        }
        total += read;
      }
    }
    try {
      if (total > YamlSubset.MAX_FILE_BYTES) {
        return null;
      }
      byte[] bytes = new byte[total];
      System.arraycopy(buffer, 0, bytes, 0, total);
      return bytes;
    } finally {
      Arrays.fill(buffer, (byte) 0);
    }
  }
}
