/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.ContextStore;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per class loader bit sets of the instrumentation installer (module bit = 1 &lt;&lt; moduleId):
 * witness evaluated, witness matched, helpers injected. Loaders are held weakly ({@link
 * ContextStore}); the bootstrap loader ({@code null}) has its own array. Bits are set atomically.
 */
final class LoaderState {
  static final int WITNESS_EVALUATED = 0;
  static final int WITNESS_MATCHED = 1;
  static final int HELPERS_INJECTED = 2;

  private static final ContextStore.Factory<AtomicLongArray> NEW_BITS =
      new ContextStore.Factory<AtomicLongArray>() {
        @Override
        public AtomicLongArray create() {
          return new AtomicLongArray(3);
        }
      };

  private final ContextStore<ClassLoader, AtomicLongArray> store =
      ContextStore.of(ClassLoader.class, AtomicLongArray.class);
  private final AtomicLongArray bootstrap = new AtomicLongArray(3);

  boolean has(ClassLoader loader, int set, int moduleId) {
    AtomicLongArray bits = loader == null ? bootstrap : store.get(loader);
    return bits != null && (bits.get(set) & (1L << moduleId)) != 0;
  }

  void add(ClassLoader loader, int set, int moduleId) {
    AtomicLongArray bits = loader == null ? bootstrap : store.computeIfAbsent(loader, NEW_BITS);
    if (bits == null) {
      return;
    }
    long bit = 1L << moduleId;
    while (true) {
      long current = bits.get(set);
      if ((current & bit) != 0 || bits.compareAndSet(set, current, current | bit)) {
        return;
      }
    }
  }
}
