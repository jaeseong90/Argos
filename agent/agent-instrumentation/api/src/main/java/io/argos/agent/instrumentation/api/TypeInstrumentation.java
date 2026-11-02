/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** The transformation of one type (or one family of types) of a module (10 §5.1). */
public interface TypeInstrumentation {
  /**
   * The types to transform. Prefer {@code named(..)} and {@code nameStartsWith(..)}; subtype
   * matching only through {@link SafeMatchers} (jdbc, executor, servlet filters).
   */
  ElementMatcher<TypeDescription> typeMatcher();

  /** Registers the advice of the matched type's methods. */
  void transform(TypeTransformer transformer);
}
