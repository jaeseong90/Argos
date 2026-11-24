/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.log.AgentLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The fixed rules of {@code instrumentation.custom-methods} (SEC-25, 16 §5.6, 10 §6.10 "매처"): which
 * items are used and which classes an item may ever match. Immutable apart from the per-item match
 * count and disabled flag; thread-safe. WO-17 uses it to choose the classes of a remote
 * retransformation ({@link Retransformer}); WO-18's custom module reads {@link #current()} in its
 * type and method matchers at every transformation ({@link Item#matchesClass(String)}, {@link
 * Item#matchesMethod(String)}) and counts the classes it matches at load time ({@link
 * Item#countClass(String)}).
 *
 * <ul>
 *   <li>An item is {@code <class glob>#<method glob>[|<as-transaction>]} ({@code
 *       KeySpec.Format.CUSTOM_METHOD}); globs: {@code *} within a package segment (any characters
 *       of a method name), {@code **} across segments, {@code ?} one character. The step is always
 *       {@code METHOD} (16 §1.4). An item with a step type suffix of the earlier format ({@code
 *       |SQL}, {@code |METHOD|true}, ...) is ignored ({@link Item#stepTypeSuffix()}, ADR-0010 C-237
 *       (5)); a pushed value with such an item is rejected as a whole ({@link
 *       #REASON_STEP_TYPE_SUFFIX}).
 *   <li>An item whose class glob starts with a forbidden prefix ({@link #FORBIDDEN_PREFIXES}:
 *       {@code java.}, {@code javax.}, {@code jakarta.}, {@code jdk.}, {@code sun.}, {@code
 *       com.sun.}, {@code io.argos.} (the agent's own and shaded classes), {@code net.bytebuddy.})
 *       is ignored ({@link Item#ignored()}); {@link #warnIgnored} writes one WARN per such item.
 *       The prefix cannot be bypassed: whatever the glob (one starting with {@code *} included), a
 *       class under a forbidden prefix never matches, nor does a generated class ({@link
 *       #isGeneratedClass}: lambdas, CGLIB, JDK and other runtime proxies).
 *   <li>An item that matches more than {@code
 *       instrumentation.custom-methods.max-classes-per-pattern} classes (default 500) is disabled
 *       ({@link Item#disabled()}), with one WARN and the self-diagnostics counter {@link
 *       #disabledCount()}.
 * </ul>
 *
 * <p>Log lines carry item positions, prefixes from {@link #FORBIDDEN_PREFIXES} and numbers, never
 * the item text.
 */
public final class CustomMethodPolicy {
  /** Class name prefixes no custom method item may match (16 §5.6, 10 §6.10). */
  public static final List<String> FORBIDDEN_PREFIXES =
      Collections.unmodifiableList(
          java.util.Arrays.asList(
              "java.",
              "javax.",
              "jakarta.",
              "jdk.",
              "sun.",
              "com.sun.",
              "io.argos.",
              // built at run time: Shadow would relocate the literal (checkOwnRelocatedLiterals)
              String.join(".", "net", "bytebuddy", "")));

  /** The default of {@code instrumentation.custom-methods.max-classes-per-pattern}. */
  public static final int DEFAULT_MAX_CLASSES_PER_PATTERN = 500;

  /** The {@code RejectedKey.message} reason of a pushed item with a forbidden prefix. */
  public static final String REASON_FORBIDDEN = "forbidden_pattern";

  /**
   * The {@code RejectedKey.message} reason of a pushed item with a step type suffix (ADR-0010 C-237
   * (5); the key is rejected {@code FORBIDDEN_PATTERN} as a whole, like {@link #REASON_FORBIDDEN}).
   */
  public static final String REASON_STEP_TYPE_SUFFIX = "step_type_suffix";

  /** The WARN and rejection text of an item with a step type suffix. */
  public static final String STEP_TYPE_SUFFIX_TEXT =
      "a step type suffix is not supported (custom methods always record METHOD steps)";

  private static final CustomMethodPolicy EMPTY =
      new CustomMethodPolicy(Collections.<Item>emptyList(), DEFAULT_MAX_CLASSES_PER_PATTERN);

  private static volatile CustomMethodPolicy current = EMPTY;

  private final List<Item> items;
  private final int maxClassesPerPattern;
  private final List<String> rawItems;

  private CustomMethodPolicy(List<Item> items, int maxClassesPerPattern, List<String> rawItems) {
    this.items = Collections.unmodifiableList(items);
    this.maxClassesPerPattern = maxClassesPerPattern;
    this.rawItems = rawItems;
  }

  private CustomMethodPolicy(List<Item> items, int maxClassesPerPattern) {
    this(items, maxClassesPerPattern, Collections.<String>emptyList());
  }

  /**
   * The policy of the configuration in force: the local one from agent start ({@link
   * #installLocal}, before the agent builder is installed, so that the custom module's matcher sees
   * it for every class it is asked about), then the one of each accepted remote change.
   */
  public static CustomMethodPolicy current() {
    return current;
  }

  /** Makes {@code policy} the one {@link #current()} returns (null: the empty policy). */
  public static void install(CustomMethodPolicy policy) {
    current = policy == null ? EMPTY : policy;
  }

  /**
   * Agent start (P5, before the agent builder): installs the policy of the local {@code
   * instrumentation.custom-methods} and writes one WARN per ignored item (SEC-25, ADR-0010 C-235
   * (2): a local value ignores only the forbidden item). Without items nothing is parsed. Returns
   * the installed policy.
   */
  public static CustomMethodPolicy installLocal(AgentConfig config) {
    List<String> local = config.instrumentationCustomMethods();
    if (local == null || local.isEmpty()) {
      install(null);
      return EMPTY;
    }
    CustomMethodPolicy policy = of(config);
    install(policy);
    policy.warnIgnored("local configuration:");
    return policy;
  }

  /**
   * True when this policy was built from exactly {@code items} with {@code maxClassesPerPattern}
   * (the remote wiring keeps the policy installed at start instead of resetting its counters).
   */
  public boolean isOf(List<String> items, int maxClassesPerPattern) {
    List<String> other = items == null ? Collections.<String>emptyList() : items;
    return rawItems.equals(other) && this.maxClassesPerPattern == Math.max(1, maxClassesPerPattern);
  }

  /** The policy of {@code config}'s {@code instrumentation.custom-methods}. */
  public static CustomMethodPolicy of(AgentConfig config) {
    return of(
        config.instrumentationCustomMethods(),
        config.instrumentationCustomMethodsMaxClassesPerPattern());
  }

  /**
   * The policy of {@code items} (already validated by {@code KeySpec}; an item that does not parse
   * is kept as ignored) with at most {@code maxClassesPerPattern} classes per item.
   */
  public static CustomMethodPolicy of(List<String> items, int maxClassesPerPattern) {
    List<Item> parsed = new ArrayList<Item>(items == null ? 0 : items.size());
    int max = Math.max(1, maxClassesPerPattern);
    if (items != null) {
      for (int i = 0; i < items.size(); i++) {
        parsed.add(Item.parse(i + 1, items.get(i), max));
      }
    }
    List<String> raw =
        items == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<String>(items));
    return new CustomMethodPolicy(parsed, max, raw);
  }

  /** True when the policy has no item at all (the custom module's matcher then costs nothing). */
  public boolean isEmpty() {
    return items.isEmpty();
  }

  /** Every item in configuration order, the ignored ones included. */
  public List<Item> items() {
    return items;
  }

  /** {@code instrumentation.custom-methods.max-classes-per-pattern} of this policy. */
  public int maxClassesPerPattern() {
    return maxClassesPerPattern;
  }

  /** Items ignored for a forbidden prefix or a step type suffix (or because they do not parse). */
  public int ignoredCount() {
    int n = 0;
    for (Item item : items) {
      if (item.ignored()) {
        n++;
      }
    }
    return n;
  }

  /** Items disabled for matching too many classes (self-diagnostics, 16 §5.6). */
  public int disabledCount() {
    int n = 0;
    for (Item item : items) {
      if (item.disabled()) {
        n++;
      }
    }
    return n;
  }

  /** Items that are neither ignored nor disabled. */
  public List<Item> activeItems() {
    List<Item> active = new ArrayList<Item>(items.size());
    for (Item item : items) {
      if (item.active()) {
        active.add(item);
      }
    }
    return active;
  }

  /**
   * The forbidden prefix {@code item}'s class glob starts with, or null (a raw item string of
   * {@code instrumentation.custom-methods}).
   */
  public static String forbiddenPrefixOf(String item) {
    if (item == null) {
      return null;
    }
    int hash = item.indexOf('#');
    String classGlob = (hash < 0 ? item : item.substring(0, hash)).trim();
    return forbiddenPrefixOfClass(classGlob);
  }

  /**
   * True when {@code item} (a raw item string of {@code instrumentation.custom-methods}) carries a
   * step type suffix of the earlier format: {@code <class>#<method>|<STEP TYPE>[|<bool>]}. The
   * current format allows only {@code |true} or {@code |false} after the method glob (ADR-0010
   * C-237 (5)).
   */
  public static boolean hasStepTypeSuffix(String item) {
    if (item == null) {
      return false;
    }
    String[] parts = item.trim().split("\\|", -1);
    if (parts.length < 2) {
      return false;
    }
    return parts.length > 2 || !isBoolean(parts[1].trim());
  }

  private static boolean isBoolean(String text) {
    return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
  }

  /** True when no custom method item may ever match the class {@code className}. */
  public static boolean isForbiddenClass(String className) {
    return className == null || forbiddenPrefixOfClass(className) != null;
  }

  private static String forbiddenPrefixOfClass(String name) {
    for (String prefix : FORBIDDEN_PREFIXES) {
      if (name.startsWith(prefix)) {
        return prefix;
      }
    }
    return null;
  }

  /**
   * True for a class the runtime or a framework generated (never a custom method target, 10 §6.10):
   * lambdas and other hidden classes ({@code $$Lambda}), CGLIB and Spring CGLIB ({@code
   * $$EnhancerBy...$$}, {@code $$SpringCGLIB$$}, {@code $$FastClass...}), Javassist ({@code
   * $$_javassist}), JDK proxies ({@code $Proxy<n>}), ByteBuddy and Hibernate proxies ({@code
   * $ByteBuddy$}, {@code $HibernateProxy$}); any binary name with {@code $$} counts.
   */
  public static boolean isGeneratedClass(String className) {
    if (className == null) {
      return true;
    }
    if (className.contains("$$")
        || className.contains("$ByteBuddy$")
        || className.contains("$HibernateProxy$")
        || className.indexOf('/') >= 0) {
      return true;
    }
    String simple = className.substring(className.lastIndexOf('.') + 1);
    if (!simple.startsWith(PROXY) || simple.length() == PROXY.length()) {
      return false;
    }
    for (int i = PROXY.length(); i < simple.length(); i++) {
      if (!Character.isDigit(simple.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static final String PROXY = "$Proxy";

  /** What {@link #select} chose among the loaded classes. */
  public static final class Selection {
    private final List<Class<?>> classes;
    private final List<Item> newlyDisabled;

    Selection(List<Class<?>> classes, List<Item> newlyDisabled) {
      this.classes = Collections.unmodifiableList(classes);
      this.newlyDisabled = Collections.unmodifiableList(newlyDisabled);
    }

    /** The distinct classes matched by an active item (each class once). */
    public List<Class<?>> classes() {
      return classes;
    }

    /** Items this selection disabled for matching too many classes. */
    public List<Item> newlyDisabled() {
      return newlyDisabled;
    }
  }

  /** Whether a loaded class may be retransformed ({@code Instrumentation.isModifiableClass}). */
  public interface Modifiable {
    boolean test(Class<?> type);
  }

  /**
   * The loaded classes the active items match (class glob only; methods are WO-18's matcher):
   * arrays, primitives, generated classes, classes under a forbidden prefix and classes {@code
   * modifiable} refuses are left out. An item that matches more than {@link
   * #maxClassesPerPattern()} classes is disabled here and contributes none ({@link
   * Selection#newlyDisabled()}; the caller warns, {@link #warnDisabled}).
   */
  public Selection select(Class<?>[] loaded, Modifiable modifiable) {
    List<Item> active = activeItems();
    IdentityHashMap<Class<?>, Boolean> chosen = new IdentityHashMap<Class<?>, Boolean>();
    List<Class<?>> ordered = new ArrayList<Class<?>>();
    List<Item> disabled = new ArrayList<Item>();
    if (active.isEmpty() || loaded == null) {
      return new Selection(ordered, disabled);
    }
    List<List<Class<?>>> perItem = new ArrayList<List<Class<?>>>(active.size());
    for (int i = 0; i < active.size(); i++) {
      perItem.add(new ArrayList<Class<?>>());
    }
    for (Class<?> type : loaded) {
      if (type == null || type.isArray() || type.isPrimitive()) {
        continue;
      }
      String name = type.getName();
      if (isForbiddenClass(name) || isGeneratedClass(name)) {
        continue;
      }
      for (int i = 0; i < active.size(); i++) {
        List<Class<?>> matches = perItem.get(i);
        if (matches.size() <= maxClassesPerPattern && active.get(i).classMatches(name)) {
          matches.add(type);
        }
      }
    }
    for (int i = 0; i < active.size(); i++) {
      Item item = active.get(i);
      List<Class<?>> matches = perItem.get(i);
      if (matches.size() > maxClassesPerPattern) {
        if (item.disable()) {
          disabled.add(item);
        }
        continue;
      }
      for (Class<?> type : matches) {
        if (!chosen.containsKey(type) && (modifiable == null || modifiable.test(type))) {
          chosen.put(type, Boolean.TRUE);
          ordered.add(type);
        }
      }
    }
    return new Selection(ordered, disabled);
  }

  /** One WARN per ignored item (16 §5.6), {@code origin} saying where the value came from. */
  public void warnIgnored(String origin) {
    for (Item item : items) {
      if (item.ignored()) {
        warnItem(origin, item);
      }
    }
  }

  /** One WARN per item in {@code disabled} (16 §5.6). */
  public void warnDisabled(List<Item> disabled) {
    for (Item item : disabled) {
      warnDisabled(item);
    }
  }

  /** The WARN of one disabled item (16 §5.6): its position and the limit, never the item text. */
  public static void warnDisabled(Item item) {
    AgentLogger.warn(
        "instrumentation.custom-methods item {} disabled: it matches more than {} classes"
            + " (instrumentation.custom-methods.max-classes-per-pattern)",
        item.position(),
        item.maxClasses);
  }

  /**
   * The WARN of an ignored item: its position, the constant reason and the forbidden prefix (from
   * {@link #FORBIDDEN_PREFIXES}), never the item text.
   */
  private static void warnItem(String origin, Item item) {
    String prefix = item.forbiddenPrefix();
    String reason;
    if (prefix != null) {
      reason = "the class pattern starts with the forbidden prefix " + prefix;
    } else if (item.stepTypeSuffix()) {
      reason = STEP_TYPE_SUFFIX_TEXT;
    } else {
      reason = "not a valid <class glob>#<method glob> item";
    }
    AgentLogger.warn(
        "{} {} item {} ignored: {}",
        origin,
        ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS,
        item.position(),
        reason);
  }

  /** One {@code instrumentation.custom-methods} item. */
  public static final class Item {
    /** {@link #countClass}: counted, the item stays active. */
    public static final int COUNTED = 0;

    /** {@link #countClass}: the item is ignored or was disabled before this call. */
    public static final int INACTIVE = 1;

    /** {@link #countClass}: this call passed the limit and disabled the item. */
    public static final int DISABLED_NOW = 2;

    private final int position;
    private final String classGlob;
    private final String methodGlob;
    private final boolean stepTypeSuffix;
    private final boolean asTransaction;
    private final String forbiddenPrefix;
    private final boolean valid;
    private final Pattern classPattern;
    private final Pattern methodPattern;
    private final int maxClasses;
    private final AtomicInteger matched = new AtomicInteger();
    private final AtomicBoolean disabled = new AtomicBoolean();

    /**
     * Interned names of the classes {@link #countClass} counted: at most {@code maxClasses + 1}
     * (plus a few of a concurrent race), since nothing is added once the item is disabled.
     */
    private final Set<String> counted =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private Item(
        int position,
        String classGlob,
        String methodGlob,
        boolean stepTypeSuffix,
        boolean asTransaction,
        boolean valid,
        int maxClasses) {
      this.position = position;
      this.maxClasses = maxClasses;
      this.classGlob = classGlob;
      this.methodGlob = methodGlob;
      this.stepTypeSuffix = stepTypeSuffix;
      this.asTransaction = asTransaction;
      this.valid = valid;
      this.forbiddenPrefix = forbiddenPrefixOfClass(classGlob);
      boolean usable = valid && forbiddenPrefix == null && !stepTypeSuffix;
      this.classPattern = usable ? globToRegex(classGlob, true) : null;
      this.methodPattern = usable ? globToRegex(methodGlob, false) : null;
    }

    static Item parse(int position, String raw, int maxClasses) {
      String item = raw == null ? "" : raw.trim();
      String[] parts = item.split("\\|", -1);
      int hash = parts[0].indexOf('#');
      if (hash <= 0 || hash == parts[0].length() - 1 || parts.length > 3) {
        String classGlob = hash < 0 ? parts[0] : parts[0].substring(0, hash);
        return new Item(position, classGlob.trim(), "", false, false, false, maxClasses);
      }
      boolean suffix = hasStepTypeSuffix(item);
      boolean asTransaction =
          !suffix && parts.length == 2 && "true".equalsIgnoreCase(parts[1].trim());
      return new Item(
          position,
          parts[0].substring(0, hash).trim(),
          parts[0].substring(hash + 1).trim(),
          suffix,
          asTransaction,
          true,
          maxClasses);
    }

    /** 1-based position in {@code instrumentation.custom-methods}. */
    public int position() {
      return position;
    }

    public String classGlob() {
      return classGlob;
    }

    public String methodGlob() {
      return methodGlob;
    }

    /**
     * True for an item with a step type suffix of the earlier format ({@code |SQL}, {@code
     * |METHOD|true}, ...): ignored, since the step is always {@code METHOD} (ADR-0010 C-237 (5)).
     */
    public boolean stepTypeSuffix() {
      return stepTypeSuffix;
    }

    public boolean asTransaction() {
      return asTransaction;
    }

    /** The forbidden prefix the class glob starts with, or null. */
    public String forbiddenPrefix() {
      return forbiddenPrefix;
    }

    /** Ignored: a forbidden prefix, a step type suffix, or not a valid item. Never matches. */
    public boolean ignored() {
      return !valid || forbiddenPrefix != null || stepTypeSuffix;
    }

    /** Disabled for matching more classes than the per-item limit. Never matches afterwards. */
    public boolean disabled() {
      return disabled.get();
    }

    /** Neither ignored nor disabled. */
    public boolean active() {
      return !ignored() && !disabled.get();
    }

    /**
     * True when this active item matches the class {@code className}: the class glob matches, and
     * the class is neither under a forbidden prefix nor generated.
     */
    public boolean matchesClass(String className) {
      return active()
          && className != null
          && !isForbiddenClass(className)
          && !isGeneratedClass(className)
          && classMatches(className);
    }

    /** True when this active item's method glob matches {@code methodName}. */
    public boolean matchesMethod(String methodName) {
      return active() && methodName != null && methodPattern.matcher(methodName).matches();
    }

    /**
     * Counts one more matched class without remembering which ({@link #countClass} counts each
     * class once and is what the custom module uses); returns false, and disables the item, when
     * the count passes {@code max-classes-per-pattern}. True for a still-active item.
     */
    public boolean countMatch() {
      if (!active()) {
        return false;
      }
      if (matched.incrementAndGet() > maxClasses) {
        disable();
        return false;
      }
      return true;
    }

    /**
     * The class-load counter of the custom module's matcher (WO-18, 10 §6.10 "매처"): counts the
     * class {@code className} once, however often it is matched (retransformation, several class
     * loaders with the same name). Returns {@link #COUNTED} while the item stays active, {@link
     * #DISABLED_NOW} exactly once, to the call that passed {@code max-classes-per-pattern} and
     * disabled the item (the caller warns, {@link CustomMethodPolicy#warnDisabled(Item)}), and
     * {@link #INACTIVE} for an ignored or already disabled item.
     */
    public int countClass(String className) {
      if (!active() || className == null) {
        return INACTIVE;
      }
      if (counted.contains(className) || !counted.add(className.intern())) {
        return COUNTED;
      }
      if (matched.incrementAndGet() > maxClasses) {
        return disable() ? DISABLED_NOW : INACTIVE;
      }
      return COUNTED;
    }

    /**
     * The names {@link #countClass} counted (interned): after {@link #DISABLED_NOW}, the classes
     * whose methods may carry this item's advice.
     */
    public List<String> countedClassNames() {
      return new ArrayList<String>(counted);
    }

    boolean classMatches(String className) {
      return classPattern != null && classPattern.matcher(className).matches();
    }

    /** Disables the item; true when this call did. */
    boolean disable() {
      if (ignored()) {
        return false;
      }
      return disabled.compareAndSet(false, true);
    }
  }

  /**
   * A glob as a regular expression: {@code **} any characters, {@code *} any characters but {@code
   * .} in a class glob (any in a method glob), {@code ?} one such character; everything else
   * literal.
   */
  static Pattern globToRegex(String glob, boolean className) {
    String one = className ? "[^.]" : ".";
    StringBuilder regex = new StringBuilder(glob.length() + 16);
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          regex.append(".*");
          i++;
        } else {
          regex.append(one).append('*');
        }
      } else if (c == '?') {
        regex.append(one);
      } else if (Character.isLetterOrDigit(c) || c == '_') {
        regex.append(c);
      } else {
        regex.append('\\').append(c);
      }
    }
    return Pattern.compile(regex.toString());
  }
}
