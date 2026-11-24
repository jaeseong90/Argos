/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The one type instrumentation of the custom module: every class an active {@code
 * instrumentation.custom-methods} item matches ({@link CustomMatchers.Types}); the step advice on
 * the methods of plain items, the transaction advice on the methods of {@code as-transaction} items
 * (a method that both match gets the transaction advice only, which records a step inside a
 * transaction as well).
 */
final class CustomMethodInstrumentation implements TypeInstrumentation {
  private static final CustomMatchers.Types TYPES = new CustomMatchers.Types();

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return TYPES;
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdvice(new CustomMatchers.Methods(false), CustomMethodAdvice.class.getName());
    transformer.applyAdvice(
        new CustomMatchers.Methods(true), CustomTransactionAdvice.class.getName());
  }
}
