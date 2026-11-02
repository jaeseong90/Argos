/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.modulefixture;

/** Violating helper fixture: listed before the helper it extends (dependency order). */
public final class SubHelper extends StatefulHelper {
  private SubHelper() {}
}
