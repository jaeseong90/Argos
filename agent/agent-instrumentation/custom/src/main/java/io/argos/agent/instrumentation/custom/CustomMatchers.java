/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.core.command.CustomMethodPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The matchers of the custom module (10 §6.10 "매처", SEC-25, 16 §5.6). They run in the agent class
 * loader (never inlined) and read {@link CustomMethodPolicy#current()} at every call, so a
 * retransformation applies the policy installed for it.
 *
 * <ul>
 *   <li>{@link Types}: a class an active item's class glob matches, unless under a forbidden prefix
 *       or generated ({@link CustomMethodPolicy#isForbiddenClass}, {@link
 *       CustomMethodPolicy#isGeneratedClass}), an interface or an annotation. Each matched class is
 *       counted for its items ({@link CustomMethodPolicy.Item#countClass}); the class that passes
 *       {@code max-classes-per-pattern} disables the item with one WARN, and the classes the item
 *       had matched are marked {@linkplain DisabledClasses disabled} so that the advice already in
 *       them stops recording without a retransformation. A class whose declared methods no item
 *       names is counted but not transformed.
 *   <li>{@link Methods}: a declared method (no constructor, type initializer, abstract, native,
 *       bridge or synthetic method, so no lambda body) whose name an active item's method glob
 *       matches for its declaring class.
 * </ul>
 *
 * Neither matcher ever throws: a failure counts as "no match".
 */
final class CustomMatchers {
  private CustomMatchers() {}

  /** The type matcher (see the class comment). */
  static final class Types extends ElementMatcher.Junction.AbstractBase<TypeDescription> {
    @Override
    public boolean matches(TypeDescription type) {
      CustomMethodPolicy policy = CustomMethodPolicy.current();
      if (policy.isEmpty()) {
        return false;
      }
      try {
        return matches(policy, type);
      } catch (Throwable t) {
        return false;
      }
    }

    private static boolean matches(CustomMethodPolicy policy, TypeDescription type) {
      String name = type.getName();
      if (CustomMethodPolicy.isForbiddenClass(name) || CustomMethodPolicy.isGeneratedClass(name)) {
        return false;
      }
      List<CustomMethodPolicy.Item> items = policy.items();
      boolean kindChecked = false;
      boolean matched = false;
      for (int i = 0; i < items.size(); i++) {
        CustomMethodPolicy.Item item = items.get(i);
        if (!item.matchesClass(name)) {
          continue;
        }
        if (!kindChecked) {
          if (type.isInterface() || type.isAnnotation()) {
            return false;
          }
          kindChecked = true;
        }
        int counted = item.countClass(name);
        if (counted == CustomMethodPolicy.Item.COUNTED) {
          matched = true;
        } else if (counted == CustomMethodPolicy.Item.DISABLED_NOW) {
          CustomMethodPolicy.warnDisabled(item);
          DisabledClasses.disable(policy, item);
        }
      }
      if (!matched || !anyMethod(policy, type)) {
        return false;
      }
      DisabledClasses.enable(name);
      return true;
    }

    /** True when an item names one of the declared methods of {@code type}. */
    private static boolean anyMethod(CustomMethodPolicy policy, TypeDescription type) {
      for (MethodDescription.InDefinedShape method : type.getDeclaredMethods()) {
        if (eligible(method) && itemsOf(policy, type.getName(), method.getInternalName()) != 0) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String toString() {
      return "customMethods()";
    }
  }

  /** The method matcher of the step advice ({@code asTransaction=false}) or the transaction one. */
  static final class Methods extends ElementMatcher.Junction.AbstractBase<MethodDescription> {
    private final boolean asTransaction;

    Methods(boolean asTransaction) {
      this.asTransaction = asTransaction;
    }

    @Override
    public boolean matches(MethodDescription method) {
      try {
        CustomMethodPolicy policy = CustomMethodPolicy.current();
        if (policy.isEmpty() || !eligible(method)) {
          return false;
        }
        int kinds =
            itemsOf(
                policy, method.getDeclaringType().asErasure().getName(), method.getInternalName());
        return asTransaction ? (kinds & TRANSACTION) != 0 : kinds == STEP;
      } catch (Throwable t) {
        return false;
      }
    }

    @Override
    public String toString() {
      return asTransaction ? "customTransactionMethods()" : "customStepMethods()";
    }
  }

  /** {@link #itemsOf} bit: a plain item names the method. */
  static final int STEP = 1;

  /** {@link #itemsOf} bit: an {@code as-transaction} item names the method. */
  static final int TRANSACTION = 2;

  /** The kinds of active items ({@link #STEP}, {@link #TRANSACTION}) that name the method. */
  static int itemsOf(CustomMethodPolicy policy, String className, String methodName) {
    int kinds = 0;
    List<CustomMethodPolicy.Item> items = policy.items();
    for (int i = 0; i < items.size(); i++) {
      CustomMethodPolicy.Item item = items.get(i);
      if (item.matchesClass(className) && item.matchesMethod(methodName)) {
        kinds |= item.asTransaction() ? TRANSACTION : STEP;
      }
    }
    return kinds;
  }

  /** A method the advice may go into (10 §6.10: no constructor, abstract, bridge, synthetic). */
  static boolean eligible(MethodDescription method) {
    return method.isMethod()
        && !method.isAbstract()
        && !method.isNative()
        && !method.isBridge()
        && !method.isSynthetic();
  }

  /**
   * Classes whose custom advice must stop recording because their item was disabled after they were
   * transformed (10 §6.10: "500 초과 시 패턴 비활성", without a retransformation). The advice cannot reach
   * the core (B-2), so the mark lives in a bootstrap store the advice reads: {@code
   * ContextStore.of(CharSequence.class, Boolean.class)}, keyed by the interned binary class name,
   * which is the same object as the advice's {@code @Advice.Origin("#t")} constant. The names are
   * held strongly here (at most {@value #MAX_NAMES}), since the store's keys are weak.
   */
  static final class DisabledClasses {
    /** Upper bound of names held; a disabled item beyond it keeps recording in its classes. */
    static final int MAX_NAMES = 16_384;

    private static final Set<String> HELD =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private DisabledClasses() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ContextStore<Object, Boolean> store() {
      return (ContextStore) ContextStore.of(CharSequence.class, Boolean.class);
    }

    /**
     * Marks the classes {@code item} counted, except those another active item of {@code policy}
     * still matches (their advice keeps recording for that item).
     */
    static void disable(CustomMethodPolicy policy, CustomMethodPolicy.Item item) {
      ContextStore<Object, Boolean> store = store();
      for (String name : item.countedClassNames()) {
        if (matchedByOther(policy, item, name)) {
          continue;
        }
        if (HELD.size() >= MAX_NAMES && !HELD.contains(name)) {
          return;
        }
        String key = name.intern();
        HELD.add(key);
        store.put(key, Boolean.TRUE);
      }
    }

    /** Clears the mark of a class an active item matches again (a retransformation). */
    static void enable(String name) {
      ContextStore<Object, Boolean> store = store();
      if (store.size() == 0 || !HELD.contains(name)) {
        return;
      }
      String key = name.intern();
      store.remove(key);
      HELD.remove(key);
    }

    /** True when the class {@code name} is marked (tests). */
    static boolean isDisabled(String name) {
      return store().get(name.intern()) != null;
    }

    private static boolean matchedByOther(
        CustomMethodPolicy policy, CustomMethodPolicy.Item item, String name) {
      for (CustomMethodPolicy.Item other : policy.items()) {
        if (other != item && other.matchesClass(name)) {
          return true;
        }
      }
      return false;
    }
  }
}
