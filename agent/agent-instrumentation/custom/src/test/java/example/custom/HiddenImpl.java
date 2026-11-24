/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

/** The implementation behind the JDK proxy of ProxyCaller. */
class HiddenImpl implements Hidden {
  @Override
  public int value() {
    return 7;
  }
}
