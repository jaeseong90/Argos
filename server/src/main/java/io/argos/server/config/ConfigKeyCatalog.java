/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * Every key of {@link ArgosProperties} with its type and default, derived from the record tree: the
 * source of the config-key snapshot (T-API-009), of the effective configuration ({@link
 * EffectiveServerConfig}) and of the key names in validation messages.
 *
 * <p>Key names follow the binder: a component {@code txPerSecond} is {@code tx-per-second}, a digit
 * after a letter starts a word ({@code metricsPer5s} is {@code metrics-per-5s}; the binder ignores
 * dashes when it matches names, so both spellings bind), and {@link Name} overrides the name. The
 * {@link Name} of a record component is read where the binder reads it: on the parameter of the
 * canonical constructor (javac copies the annotation there and to the field, since its targets are
 * {@code PARAMETER} and {@code FIELD}; {@link RecordComponent#getAnnotation} never sees it), then
 * on the field. {@code ConfigKeyCatalogTest} binds every key by its catalog name to prove the names
 * are the binder's. A nested record is a section; anything else is a leaf key (lists and maps
 * included).
 */
public final class ConfigKeyCatalog {

  /** Root of the tree. */
  public static final String PREFIX = "argos";

  /**
   * Last segments of secret value keys; their values are never shown. {@code jwt-key} and {@code
   * jwt-previous-key} are the access-token signing keys (WO-24, 16 §7.1); {@code previous} is the
   * previous master key of {@code security.master-key.previous} (WO-25, 16 §8.2).
   */
  static final Set<String> SECRET_NAMES =
      Set.of("password", "token", "master-key", "jwt-key", "jwt-previous-key", "previous");

  // Initialization order matters: build() uses SIMPLE_TYPES.
  private static final Map<Class<?>, String> SIMPLE_TYPES = simpleTypes();
  private static final List<ConfigKey> KEYS = build();
  private static final Map<String, ConfigKey> BY_JAVA_PATH = byJavaPath(KEYS);

  private ConfigKeyCatalog() {}

  /** What kind of value a key holds. */
  public enum Kind {
    /** An ordinary setting. */
    VALUE,
    /** A password, token or key given as a value: shown as {@code ****}. */
    SECRET,
    /** The path of a file holding a secret (the path is shown, the file is not read). */
    SECRET_FILE
  }

  /**
   * One configuration key.
   *
   * @param name the key, e.g. {@code argos.collector.quota.metrics-per-5s}
   * @param javaPath the record path, e.g. {@code collector.quota.metricsPer5s}
   * @param type the type in the snapshot notation ({@code int}, {@code duration}, {@code
   *     list<string>}, {@code enum(full|masked|off)}, ...)
   * @param defaultValue the default, rendered by {@link #render(Object)}
   * @param kind value, secret or secret file
   */
  public record ConfigKey(
      String name, String javaPath, String type, String defaultValue, Kind kind) {

    /** The bound value of this key in {@code properties}. */
    public Object valueIn(ArgosProperties properties) {
      Object current = properties;
      for (String accessor : javaPath.split("\\.", -1)) {
        current = component(current, accessor);
        if (current == null) {
          return null;
        }
      }
      return current;
    }

    /** The snapshot line: name, type and default separated by tabs. */
    public String snapshotLine() {
      return name + "\t" + type + "\t" + defaultValue;
    }
  }

  /** Every key, sorted by name. */
  public static List<ConfigKey> keys() {
    return KEYS;
  }

  /**
   * The key of a record path such as {@code collector.limits.maxStepsPerBatch} (a Bean Validation
   * property path); paths below a leaf (list elements, map entries) map to their leaf key.
   */
  public static String keyOf(String javaPath) {
    String path = javaPath.replaceAll("\\[[^]]*]", "").replaceAll("<[^>]*>", "");
    while (!path.isEmpty()) {
      ConfigKey key = BY_JAVA_PATH.get(path);
      if (key != null) {
        return key.name();
      }
      int dot = path.lastIndexOf('.');
      if (dot < 0) {
        break;
      }
      path = path.substring(0, dot);
    }
    return PREFIX + "." + javaPath;
  }

  /**
   * The binder name of a record component: the {@link Name} of its canonical constructor parameter
   * (where the binder reads it), else the {@link Name} of its field, else {@link #kebab}.
   */
  static String keyName(RecordComponent component) {
    Class<?> record = component.getDeclaringRecord();
    RecordComponent[] components = record.getRecordComponents();
    Class<?>[] types = new Class<?>[components.length];
    int index = -1;
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
      if (components[i].getName().equals(component.getName())) {
        index = i;
      }
    }
    try {
      Parameter parameter = record.getDeclaredConstructor(types).getParameters()[index];
      Name name = parameter.getAnnotation(Name.class);
      if (name != null) {
        return name.value();
      }
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("No canonical constructor on " + record.getName(), e);
    }
    try {
      Field field = record.getDeclaredField(component.getName());
      Name name = field.getAnnotation(Name.class);
      if (name != null) {
        return name.value();
      }
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(
          "No field " + component.getName() + " on " + record.getName(), e);
    }
    return kebab(component.getName());
  }

  /** {@code metricsPer5s} to {@code metrics-per-5s}. */
  static String kebab(String camel) {
    StringBuilder out = new StringBuilder(camel.length() + 8);
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      boolean wordStart =
          i > 0
              && (Character.isUpperCase(c)
                  || (Character.isDigit(c) && Character.isLetter(camel.charAt(i - 1))));
      if (wordStart) {
        out.append('-');
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }

  /**
   * A value in the notation of the snapshot and the effective configuration: durations and sizes in
   * their largest whole unit ({@code 30d}, {@code 256MB}), enums in lower case, {@code (none)} for
   * an unset value, {@code ""} for an empty string.
   */
  public static String render(Object value) {
    if (value == null) {
      return "(none)";
    }
    if (value instanceof String s && s.isEmpty()) {
      return "\"\"";
    }
    if (value instanceof Duration d) {
      return renderDuration(d);
    }
    if (value instanceof DataSize s) {
      return renderSize(s);
    }
    if (value instanceof ZoneId z) {
      return z.getId();
    }
    if (value instanceof Enum<?> e) {
      return enumName(e);
    }
    if (value instanceof Collection<?> c) {
      return c.stream().map(ConfigKeyCatalog::render).collect(Collectors.joining(", ", "[", "]"));
    }
    if (value instanceof Map<?, ?> m) {
      Map<String, String> sorted = new TreeMap<>();
      m.forEach((k, v) -> sorted.put(String.valueOf(k), render(v)));
      return sorted.entrySet().stream()
          .map(e -> e.getKey() + "=" + e.getValue())
          .collect(Collectors.joining(", ", "{", "}"));
    }
    return String.valueOf(value);
  }

  static String renderDuration(Duration d) {
    long millis = d.toMillis();
    if (millis != 0 && millis % 86_400_000L == 0) {
      return millis / 86_400_000L + "d";
    }
    if (millis != 0 && millis % 3_600_000L == 0) {
      return millis / 3_600_000L + "h";
    }
    if (millis != 0 && millis % 60_000L == 0) {
      return millis / 60_000L + "m";
    }
    if (millis % 1000L == 0) {
      return millis / 1000L + "s";
    }
    return millis + "ms";
  }

  static String renderSize(DataSize size) {
    long bytes = size.toBytes();
    long[] units = {1L << 40, 1L << 30, 1L << 20, 1L << 10};
    String[] names = {"TB", "GB", "MB", "KB"};
    for (int i = 0; i < units.length; i++) {
      if (bytes != 0 && bytes % units[i] == 0) {
        return bytes / units[i] + names[i];
      }
    }
    return bytes + "B";
  }

  private static String enumName(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  // --- building the catalog ----------------------------------------------------------------------

  private static List<ConfigKey> build() {
    ArgosProperties defaults =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate(PREFIX, ArgosProperties.class);
    List<ConfigKey> keys = new ArrayList<>();
    walk(ArgosProperties.class, PREFIX, "", defaults, keys);
    keys.sort((a, b) -> a.name().compareTo(b.name()));
    return Collections.unmodifiableList(keys);
  }

  private static void walk(
      Class<?> record, String keyPrefix, String javaPrefix, Object instance, List<ConfigKey> out) {
    for (RecordComponent component : record.getRecordComponents()) {
      String key = keyPrefix + "." + keyName(component);
      String javaPath =
          javaPrefix.isEmpty() ? component.getName() : javaPrefix + "." + component.getName();
      Object value = instance == null ? null : component(instance, component.getName());
      Class<?> type = component.getType();
      if (isSection(type)) {
        walk(type, key, javaPath, value, out);
      } else {
        String last = key.substring(key.lastIndexOf('.') + 1);
        Kind kind =
            SECRET_NAMES.contains(last)
                ? Kind.SECRET
                : last.endsWith("-file")
                        && SECRET_NAMES.contains(last.substring(0, last.length() - 5))
                    ? Kind.SECRET_FILE
                    : Kind.VALUE;
        out.add(
            new ConfigKey(
                key, javaPath, typeName(component.getGenericType()), render(value), kind));
      }
    }
  }

  private static boolean isSection(Class<?> type) {
    return type.isRecord() && type.getName().startsWith("io.argos.");
  }

  static String typeName(Type type) {
    if (type instanceof ParameterizedType p) {
      Class<?> raw = (Class<?>) p.getRawType();
      String args =
          Arrays.stream(p.getActualTypeArguments())
              .map(ConfigKeyCatalog::typeName)
              .collect(Collectors.joining(","));
      if (List.class.isAssignableFrom(raw)) {
        return "list<" + args + ">";
      }
      if (Map.class.isAssignableFrom(raw)) {
        return "map<" + args + ">";
      }
      return raw.getSimpleName().toLowerCase(Locale.ROOT) + "<" + args + ">";
    }
    Class<?> c = (Class<?>) type;
    if (c.isEnum()) {
      return Arrays.stream(c.getEnumConstants())
          .map(e -> enumName((Enum<?>) e))
          .collect(Collectors.joining("|", "enum(", ")"));
    }
    String name = SIMPLE_TYPES.get(c);
    return name != null ? name : c.getSimpleName().toLowerCase(Locale.ROOT);
  }

  private static Map<String, ConfigKey> byJavaPath(List<ConfigKey> keys) {
    Map<String, ConfigKey> m = new TreeMap<>();
    keys.forEach(k -> m.put(k.javaPath(), k));
    return Collections.unmodifiableMap(m);
  }

  private static Map<Class<?>, String> simpleTypes() {
    Map<Class<?>, String> m = new LinkedHashMap<>();
    m.put(String.class, "string");
    m.put(int.class, "int");
    m.put(Integer.class, "int");
    m.put(long.class, "long");
    m.put(Long.class, "long");
    m.put(double.class, "double");
    m.put(Double.class, "double");
    m.put(boolean.class, "boolean");
    m.put(Boolean.class, "boolean");
    m.put(Duration.class, "duration");
    m.put(DataSize.class, "data-size");
    m.put(ZoneId.class, "time-zone");
    return Collections.unmodifiableMap(m);
  }

  private static Object component(Object record, String name) {
    try {
      return record.getClass().getMethod(name).invoke(record);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new IllegalStateException("No accessor " + name + " on " + record.getClass(), e);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Accessor " + name + " failed", e.getCause());
    }
  }
}
