/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.bootstrap;

/** Field injection or weak map (10 §3); the factory A-9 checks. */
public final class ContextStore<K, V> {
  private K lastKey;
  private V lastValue;

  private ContextStore() {}

  public void put(K key, V value) {
    lastKey = key;
    lastValue = value;
  }

  public V get(K key) {
    return key != null && key.equals(lastKey) ? lastValue : null;
  }

  public static <K, V> ContextStore<K, V> of(Class<K> keyType, Class<V> valueType) {
    return new ContextStore<K, V>();
  }
}
