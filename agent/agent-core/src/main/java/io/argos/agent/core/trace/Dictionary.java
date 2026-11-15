/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.hash.Xxh3;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Dictionary of names (DSN-02-10, DSN-10-18, 10 §10.4, ADR-0010 C-91): {@code id = xxh3_64(UTF-8
 * text)} ({@link Xxh3#hash64(String)}, 0 remapped to 1, so 0 always means "no name"), plus a
 * bounded cache of the texts so that exporters (and the dictionary pre-send of WO-11) can resolve
 * ids. Ids depend on the text only: the same text has the same id in every instance and after every
 * restart (T-ACC-08). The placeholders {@value #UNPARSED}, {@value #OVERFLOW} and {@value #OTHER}
 * are hashed like any other text.
 *
 * <p>Bounds (N-02, 10 §14 "사전 캐시 8MB"):
 *
 * <ul>
 *   <li>per {@link DictKind}: the {@code DictionaryCache} sizes of 10 §10.4 ({@link
 *       #capacity(int)}: service 10,000, SQL 10,000, class and method 20,000, host 1,000, thread
 *       1,000, frame 50,000; the kinds 10 §10.4 does not size: metric 1,000, error group 10,000, DB
 *       target 1,000, app version 100);
 *   <li>all kinds together: {@value #MAX_BYTES} estimated bytes ({@link #estimatedBytes()});
 *   <li>one text: {@value #MAX_TEXT_CHARS} characters; a longer text keeps its id (the hash of the
 *       whole text) and only its first {@value #MAX_TEXT_CHARS} characters ({@link
 *       #truncated(long)}).
 * </ul>
 *
 * The placeholders are always kept, outside these counts (three entries at most), because they are
 * what a full name cap maps to and must stay resolvable. A text that does not fit still gets its
 * id; only the text is not kept, and {@code argos_agent_dictionary_overflow_total{kind}} counts it
 * ({@link #overflow(int)}). Lock-free: two {@link ConcurrentHashMap}s (text to id for hits without
 * hashing, id to text for exporters) and atomic counters; an application thread never waits here.
 */
public final class Dictionary {
  /** SQL that could not be normalized (10 §9.4, DSN-10-17). */
  public static final String UNPARSED = "<unparsed>";

  /** Key of statistics beyond {@code stats.max-keys} (10 §9.3). */
  public static final String OVERFLOW = "<overflow>";

  /** Name beyond a name cap (F-A29 services, 10 §8 threads). */
  public static final String OTHER = "_other_";

  /** Estimated bytes of all kept texts (10 §14 heap budget: dictionary caches 8 MB). */
  public static final long MAX_BYTES = 8L * 1024 * 1024;

  /** Characters of one kept text. */
  public static final int MAX_TEXT_CHARS = 65_536;

  /**
   * Estimated fixed bytes of one kept entry: two map nodes, a boxed id, the String header and the
   * {@link Entry} that remembers the kind (the transport pre-sends and answers {@code
   * DictionaryEntry{kind, hash, text}}, 11 §7).
   */
  static final long ENTRY_BYTES = 160L;

  /**
   * Text cache size per kind (10 §10.4 {@code DictionaryCache}, ADR-0010 C-105); the transport's
   * pre-send memory uses the same sizes.
   */
  public static int capacity(int kind) {
    switch (kind) {
      case DictKind.SERVICE:
      case DictKind.SQL:
      case DictKind.ERROR_GROUP:
        return 10_000;
      case DictKind.CLASS:
      case DictKind.METHOD:
        return 20_000;
      case DictKind.HOST:
      case DictKind.THREAD:
      case DictKind.METRIC:
      case DictKind.DB_TARGET:
        return 1_000;
      case DictKind.FRAME:
        return 50_000;
      case DictKind.APP_VERSION:
        return 100;
      default:
        return 1_000;
    }
  }

  /** A kept text, its {@link DictKind} and whether it was cut to {@link #MAX_TEXT_CHARS}. */
  private static final class Entry {
    final String text;
    final int kind;
    final boolean truncated;

    Entry(String text, int kind, boolean truncated) {
      this.text = text;
      this.kind = kind;
      this.truncated = truncated;
    }
  }

  private final AgentCounters counters;
  private final ConcurrentHashMap<String, Long> ids = new ConcurrentHashMap<String, Long>();
  private final ConcurrentHashMap<Long, Entry> texts = new ConcurrentHashMap<Long, Entry>();
  private final AtomicIntegerArray sizes = new AtomicIntegerArray(DictKind.COUNT);
  private final AtomicLongArray overflows = new AtomicLongArray(DictKind.COUNT);
  private final AtomicLong bytes = new AtomicLong();
  private final long maxBytes;

  /** A dictionary that does not report overflows to agent counters (tests, tools). */
  public Dictionary() {
    this(null, MAX_BYTES);
  }

  /** A dictionary that counts overflows in {@code counters} (may be null). */
  public Dictionary(AgentCounters counters) {
    this(counters, MAX_BYTES);
  }

  Dictionary(AgentCounters counters, long maxBytes) {
    this.counters = counters;
    this.maxBytes = maxBytes;
  }

  /** The id of {@code text} (0 for null), keeping the text while its kind and the budget allow. */
  public long id(int kind, String text) {
    return id(kind, text, false);
  }

  /**
   * {@link #id(int, String)} of a text that is itself only the start of a longer input: an SQL
   * statement the normalizer cut at {@code sql.max-length} (10 §9.4 "절단 + truncated 플래그"). A kept
   * text then reports {@link #truncated(long)} true (11 {@code DictionaryEntry.truncated}). The
   * first caller that keeps a text decides the flag.
   */
  public long id(int kind, String text, boolean truncatedInput) {
    if (text == null) {
      return 0;
    }
    Long known = ids.get(text);
    if (known != null) {
      return known.longValue();
    }
    long id = hash64(text);
    Long key = Long.valueOf(id);
    if (texts.containsKey(key)) {
      return id;
    }
    int slot = kind > 0 && kind < DictKind.COUNT ? kind : 0;
    boolean cut = text.length() > MAX_TEXT_CHARS;
    String kept = cut ? text.substring(0, MAX_TEXT_CHARS) : text;
    long cost = ENTRY_BYTES + 2L * kept.length();
    if (isPlaceholder(text)) {
      // The three placeholders are what a full name cap maps to: they are always kept (outside
      // the per-kind counts, three entries at most), so they stay resolvable.
      if (texts.putIfAbsent(key, new Entry(text, kind, false)) == null) {
        bytes.addAndGet(cost);
        ids.put(text, key);
      }
      return id;
    }
    if (!reserve(slot, kind, cost)) {
      overflows.incrementAndGet(slot);
      if (counters != null) {
        counters.incDictionaryOverflow(kind);
      }
      return id;
    }
    if (texts.putIfAbsent(key, new Entry(kept, kind, cut || truncatedInput)) != null) {
      release(slot, cost); // another thread kept it first
      return id;
    }
    if (!cut) {
      ids.put(text, key);
    }
    return id;
  }

  /** {@value #UNPARSED}, {@value #OVERFLOW} or {@value #OTHER}. */
  static boolean isPlaceholder(String text) {
    return UNPARSED.equals(text) || OVERFLOW.equals(text) || OTHER.equals(text);
  }

  private boolean reserve(int slot, int kind, long cost) {
    int limit = capacity(kind);
    while (true) {
      int size = sizes.get(slot);
      if (size >= limit) {
        return false;
      }
      if (sizes.compareAndSet(slot, size, size + 1)) {
        break;
      }
    }
    while (true) {
      long used = bytes.get();
      if (used + cost > maxBytes) {
        sizes.decrementAndGet(slot);
        return false;
      }
      if (bytes.compareAndSet(used, used + cost)) {
        return true;
      }
    }
  }

  private void release(int slot, long cost) {
    sizes.decrementAndGet(slot);
    bytes.addAndGet(-cost);
  }

  /** The text of {@code id} (its first {@value #MAX_TEXT_CHARS} characters), or null. */
  public String text(long id) {
    if (id == 0) {
      return null;
    }
    Entry entry = texts.get(Long.valueOf(id));
    return entry == null ? null : entry.text;
  }

  /** True when the kept text of {@code id} is only the start of the text that was hashed. */
  public boolean truncated(long id) {
    Entry entry = id == 0 ? null : texts.get(Long.valueOf(id));
    return entry != null && entry.truncated;
  }

  /**
   * The {@link DictKind} the text of {@code id} was first kept for (11 {@code
   * DictionaryEntry.kind}, C-16), or 0 when no text is kept. The same text has the same id whatever
   * its kind; the first kind wins.
   */
  public int kind(long id) {
    Entry entry = id == 0 ? null : texts.get(Long.valueOf(id));
    return entry == null ? 0 : entry.kind;
  }

  /** Ids whose text was not kept because the kind or the byte budget was full. */
  public long overflow(int kind) {
    return kind > 0 && kind < DictKind.COUNT ? overflows.get(kind) : 0;
  }

  /** Texts kept for {@code kind}. */
  public int size(int kind) {
    return kind > 0 && kind < DictKind.COUNT ? sizes.get(kind) : 0;
  }

  /**
   * Estimated heap bytes of the kept texts: at most {@value #MAX_BYTES}, plus the three
   * placeholders (under 1 KB together) when they arrive after the budget is used up.
   */
  public long estimatedBytes() {
    return bytes.get();
  }

  /**
   * The dictionary id of {@code text}: XXH3-64 of its UTF-8 bytes, never 0 ({@link
   * Xxh3#hash64(String)}). The same function as {@link #id(int, String)}, without the cache.
   */
  public static long hash64(String text) {
    return Xxh3.hash64(text);
  }
}
