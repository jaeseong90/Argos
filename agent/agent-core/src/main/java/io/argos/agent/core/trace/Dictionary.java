/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.DictKind;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Dictionary of names (DSN-02-10, 10 §10.4): {@code id = hash64(UTF-8 text)} plus a bounded
 * id-to-text cache per {@link DictKind} so that exporters can resolve ids. A full kind still
 * returns ids; only the text is not kept ({@link #overflow(int)}).
 *
 * <p>WO-02 uses FNV-1a 64 as the content hash. DSN-10-18 requires xxh3_64 (shared with the server);
 * {@code core.hash.Xxh3} replaces {@link #hash64(String)} in WO-10, before any id is persisted.
 */
public final class Dictionary {
  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  /** Text cache size per kind (10 §10.4 DictionaryCache sizes). */
  static int capacity(int kind) {
    switch (kind) {
      case DictKind.SERVICE:
      case DictKind.SQL:
        return 10_000;
      case DictKind.CLASS:
      case DictKind.METHOD:
        return 20_000;
      case DictKind.HOST:
      case DictKind.THREAD:
        return 1_000;
      case DictKind.FRAME:
        return 50_000;
      default:
        return 10_000;
    }
  }

  private final ConcurrentHashMap<Long, String> texts = new ConcurrentHashMap<Long, String>();
  private final AtomicIntegerArray sizes = new AtomicIntegerArray(DictKind.COUNT);
  private final AtomicLongArray overflows = new AtomicLongArray(DictKind.COUNT);

  /** The id of {@code text} (0 for null), remembering the text while the kind has room. */
  public long id(int kind, String text) {
    if (text == null) {
      return 0;
    }
    long id = hash64(text);
    Long key = Long.valueOf(id);
    if (texts.containsKey(key)) {
      return id;
    }
    int slot = kind > 0 && kind < DictKind.COUNT ? kind : 0;
    if (sizes.get(slot) >= capacity(kind)) {
      overflows.incrementAndGet(slot);
      return id;
    }
    if (texts.putIfAbsent(key, text) == null) {
      sizes.incrementAndGet(slot);
    }
    return id;
  }

  /** The text of {@code id}, or null when unknown or evicted. */
  public String text(long id) {
    return id == 0 ? null : texts.get(Long.valueOf(id));
  }

  /** Ids whose text was not kept because the kind was full. */
  public long overflow(int kind) {
    return kind > 0 && kind < DictKind.COUNT ? overflows.get(kind) : 0;
  }

  /** FNV-1a 64 over the UTF-8 encoding of {@code text}, never 0 (0 means "no name"). */
  public static long hash64(String text) {
    long hash = FNV_OFFSET;
    int length = text.length();
    for (int i = 0; i < length; i++) {
      int c = text.charAt(i);
      if (c < 0x80) {
        hash = (hash ^ c) * FNV_PRIME;
      } else if (c < 0x800) {
        hash = (hash ^ (0xC0 | (c >> 6))) * FNV_PRIME;
        hash = (hash ^ (0x80 | (c & 0x3F))) * FNV_PRIME;
      } else if (Character.isHighSurrogate((char) c)
          && i + 1 < length
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        int cp = Character.toCodePoint((char) c, text.charAt(++i));
        hash = (hash ^ (0xF0 | (cp >> 18))) * FNV_PRIME;
        hash = (hash ^ (0x80 | ((cp >> 12) & 0x3F))) * FNV_PRIME;
        hash = (hash ^ (0x80 | ((cp >> 6) & 0x3F))) * FNV_PRIME;
        hash = (hash ^ (0x80 | (cp & 0x3F))) * FNV_PRIME;
      } else {
        if (Character.isSurrogate((char) c)) {
          c = '?'; // unpaired surrogate: encoded as '?' like String.getBytes(UTF_8)
          hash = (hash ^ c) * FNV_PRIME;
          continue;
        }
        hash = (hash ^ (0xE0 | (c >> 12))) * FNV_PRIME;
        hash = (hash ^ (0x80 | ((c >> 6) & 0x3F))) * FNV_PRIME;
        hash = (hash ^ (0x80 | (c & 0x3F))) * FNV_PRIME;
      }
    }
    return hash == 0 ? 1 : hash;
  }
}
