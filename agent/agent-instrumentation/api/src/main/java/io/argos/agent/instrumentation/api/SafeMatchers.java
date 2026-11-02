/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Subtype matchers that never fail a transformation (10 §5.1): a type that cannot be resolved
 * (missing dependency, {@code ClassCircularityError}) ends that branch of the hierarchy walk
 * instead of throwing and is reported to the core's error sink. Prefer name matchers; use these for
 * jdbc, executor and servlet filters only.
 */
public final class SafeMatchers {
  /** Upper bound of types visited per match (deep or cyclic hierarchies). */
  static final int MAX_TYPES = 256;

  /** Receives resolution failures; set by the core. */
  public interface ErrorSink {
    void onResolutionError(String typeName, Throwable error);
  }

  private static volatile ErrorSink errorSink;

  private SafeMatchers() {}

  /** Installs the core's sink for resolution failures. */
  public static void setErrorSink(ErrorSink sink) {
    errorSink = sink;
  }

  /** Matches a type when it, a superclass or an implemented interface matches {@code matcher}. */
  public static ElementMatcher.Junction<TypeDescription> safeHasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return new HierarchyMatcher(matcher, false, "safeHasSuperType(" + matcher + ")");
  }

  /**
   * Matches a type when an interface in its hierarchy (itself included) matches {@code matcher}.
   * Combine with {@code not(isInterface())} to match implementations only.
   */
  public static ElementMatcher.Junction<TypeDescription> safeImplements(
      ElementMatcher<? super TypeDescription> matcher) {
    return new HierarchyMatcher(matcher, true, "safeImplements(" + matcher + ")");
  }

  static void report(String typeName, Throwable error) {
    ErrorSink sink = errorSink;
    if (sink != null) {
      try {
        sink.onResolutionError(typeName, error);
      } catch (Throwable ignored) {
        // Reporting must never fail a match.
      }
    }
  }

  private static final class HierarchyMatcher
      extends ElementMatcher.Junction.AbstractBase<TypeDescription> {
    private final ElementMatcher<? super TypeDescription> matcher;
    private final boolean interfacesOnly;
    private final String description;

    HierarchyMatcher(
        ElementMatcher<? super TypeDescription> matcher,
        boolean interfacesOnly,
        String description) {
      this.matcher = matcher;
      this.interfacesOnly = interfacesOnly;
      this.description = description;
    }

    @Override
    public boolean matches(TypeDescription target) {
      List<TypeDescription> pending = new ArrayList<TypeDescription>();
      Set<String> visited = new HashSet<String>();
      pending.add(target);
      while (!pending.isEmpty() && visited.size() < MAX_TYPES) {
        TypeDescription type = pending.remove(pending.size() - 1);
        String name;
        try {
          name = type.getName();
        } catch (Throwable e) {
          report(String.valueOf(type), e);
          continue;
        }
        if (!visited.add(name)) {
          continue;
        }
        if (matchesSelf(type, name)) {
          return true;
        }
        try {
          TypeDescription.Generic superClass = type.getSuperClass();
          if (superClass != null) {
            pending.add(superClass.asErasure());
          }
        } catch (Throwable e) {
          report(name, e);
        }
        try {
          for (TypeDefinition implemented : type.getInterfaces()) {
            pending.add(implemented.asErasure());
          }
        } catch (Throwable e) {
          report(name, e);
        }
      }
      return false;
    }

    private boolean matchesSelf(TypeDescription type, String name) {
      try {
        return (!interfacesOnly || type.isInterface()) && matcher.matches(type);
      } catch (Throwable e) {
        report(name, e);
        return false;
      }
    }

    @Override
    public String toString() {
      return description;
    }
  }
}
