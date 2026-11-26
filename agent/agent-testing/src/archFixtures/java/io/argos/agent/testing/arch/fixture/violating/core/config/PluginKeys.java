/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core.config;

/**
 * ST-91-1 violation (16 §1.4 point 3): a remote-config key whose value names a class to load, the
 * "plugin" path the closed key set rules out.
 */
public final class PluginKeys {
  @SuppressWarnings("ClassNewInstance") // the forbidden call is the point of this fixture
  public Object apply(String key, String value, ClassLoader loader) throws Exception {
    String className = "exporter".equals(key) ? value : "java.lang.Object";
    return Class.forName(className, true, loader).newInstance();
  }
}
