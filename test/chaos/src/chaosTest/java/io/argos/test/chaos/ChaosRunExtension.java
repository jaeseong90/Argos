/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Gives every scenario class the one {@link ChaosSession} of the run. The session lives in the root
 * context's store, so JUnit closes it once after the last class: the stack stops and the report is
 * written ({@link ChaosSession#close}).
 */
public final class ChaosRunExtension implements ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosRunExtension.class);

  @Override
  public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
    return parameter.getParameter().getType() == ChaosSession.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
    return context
        .getRoot()
        .getStore(NAMESPACE)
        .getOrComputeIfAbsent(
            "session",
            key -> new ChaosSession(ChaosOptions.fromSystemProperties()),
            ChaosSession.class);
  }
}
