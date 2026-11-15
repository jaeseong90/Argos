/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ingest.api.DictionaryIntake;
import io.argos.server.ingest.hash.Xxh3;
import io.argos.server.session.api.SessionMessenger;
import io.micrometer.core.instrument.Gauge;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dictionary handling of the ingest path (12 §3.5 (2), 11 §7, 16 §4.4, DSN-12-05): rows are stored
 * with the ids as sent and never wait for a text.
 *
 * <ul>
 *   <li>Texts sent by agents ({@link DictionaryIntake}) are upserted into PostgreSQL {@code
 *       dictionary} by the one {@code argos-dict-writer} thread in batches of {@value #BATCH_ROWS}
 *       rows or {@value #BATCH_MS} ms ({@code INSERT … ON CONFLICT (app_id, kind, hash64) DO UPDATE
 *       SET last_seen = now()}); texts are cut at 64 KiB of UTF-8 with {@code truncated}.
 *   <li>Caps (SEC-13, 16 §4.4): an instance may add {@code
 *       argos.collector.limits.max-dictionary-entries-per-hour} (10,000) new entries per clock hour
 *       and an app at most {@value #DEFAULT_APP_CAP} entries in total. A new entry beyond a cap is
 *       not stored; its key is remembered as overflowed and every row id equal to it is replaced by
 *       the fixed {@link #OVERFLOW_ID} ({@code xxh3_64("<overflow>")}, a dictionary row with the
 *       text {@code <overflow>} per app and kind), counted in {@code
 *       argos_dictionary_overflow_total}. An entry is new when it is neither in the memory cache
 *       nor in PostgreSQL, so an agent that sends its known entries again after a restart does not
 *       use up the cap.
 *   <li>Ids that rows reference but the server does not know are looked up in PostgreSQL once and
 *       otherwise requested from the agent ({@code DictionaryRequest} through {@link
 *       SessionMessenger}, batched per {@value #REQUEST_BATCH_MS} ms, at most {@value
 *       #MAX_REFS_PER_REQUEST} refs per message) at most {@value #MAX_ATTEMPTS} times; an attempt
 *       without a live session does not count until {@value #UNKNOWN_TTL_MS} ms passed. Ids still
 *       unknown are counted in the gauge {@code argos_dictionary_unresolved} (shown as {@code
 *       <unknown:hash>} by the API).
 * </ul>
 *
 * <p>Memory: the known and overflowed key caches hold at most {@value #CACHE_KEYS} keys each (a key
 * that falls out is looked up in PostgreSQL again); the queues and the unknown set are bounded and
 * count what they drop in {@code argos_ingest_errors_total{stage}}.
 */
@Component
public class DictionaryResolver implements DictionaryIntake, DisposableBean {

  /** Text of the overflow entry (16 §4.4). */
  public static final String OVERFLOW_TEXT = "<overflow>";

  /** The id rows get for an overflowed dictionary key. */
  public static final long OVERFLOW_ID = Xxh3.hash64(OVERFLOW_TEXT);

  /** Entries per app (16 §4.4). */
  public static final long DEFAULT_APP_CAP = 1_000_000;

  /** Upsert batch bounds (12 §3.5 (2)). */
  static final int BATCH_ROWS = 500;

  static final long BATCH_MS = 500;

  /** Request batching (12 §3.5 (2)). */
  static final long REQUEST_BATCH_MS = 100;

  static final int MAX_ATTEMPTS = 3;

  static final long ATTEMPT_SPACING_MS = 5_000;

  /** An unknown id without any live session to ask is given up after this long. */
  static final long UNKNOWN_TTL_MS = TimeUnit.HOURS.toMillis(1);

  static final int MAX_REFS_PER_REQUEST = ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE;

  static final int CACHE_KEYS = 1_000_000;

  static final int MAX_QUEUED_ENTRIES = 100_000;

  static final int MAX_UNKNOWN = 100_000;

  /** Longest stored text in UTF-8 bytes (13 §12.3). */
  static final int MAX_TEXT_BYTES = ProtocolLimits.MAX_SQL_TEXT_BYTES;

  private static final Logger LOG = LoggerFactory.getLogger(DictionaryResolver.class);

  /** A dictionary key. */
  record Key(int appId, DictionaryKind kind, long hash) {}

  /** One entry to store. */
  private record Incoming(int appId, int instanceId, DictionaryEntry entry, boolean serverEntry) {}

  /** An id that a row referenced but the server does not know. */
  private static final class Unknown {
    final int instanceId;
    final long firstSeenMs;
    int attempts;
    long nextAttemptMs;
    boolean checkedStore;

    Unknown(int instanceId, long firstSeenMs) {
      this.instanceId = instanceId;
      this.firstSeenMs = firstSeenMs;
      this.nextAttemptMs = firstSeenMs;
    }
  }

  private final IngestStore store;
  private final ObjectProvider<SessionMessenger> messengers;
  private final IngestMetrics metrics;
  private final int instanceHourCap;
  private final long appCap;
  private final Clock clock;
  private final Cache<Key, Boolean> known;
  private final Cache<Key, Boolean> overflowed;
  private final BlockingQueue<Incoming> incoming = new ArrayBlockingQueue<>(MAX_QUEUED_ENTRIES);
  private final Map<Key, Unknown> unknown = new ConcurrentHashMap<>();
  private final Set<Key> unresolved = ConcurrentHashMap.newKeySet();
  // argos-dict-writer state
  private final Map<Integer, long[]> instanceHour = new HashMap<>();
  private final Map<Integer, Long> appTotals = new HashMap<>();
  private final List<IngestStore.DictionaryRow> retry = new ArrayList<>();
  private final List<Key> retryKeys = new ArrayList<>();
  private volatile Thread thread;
  private volatile boolean stopping;

  /** The bean of the server. */
  @Autowired
  public DictionaryResolver(
      IngestStore store,
      ObjectProvider<SessionMessenger> messengers,
      IngestMetrics metrics,
      ArgosProperties properties) {
    this(
        store,
        messengers,
        metrics,
        properties.collector().limits().maxDictionaryEntriesPerHour(),
        DEFAULT_APP_CAP,
        Clock.systemUTC());
  }

  /** A resolver with explicit caps (tests). */
  public DictionaryResolver(
      IngestStore store,
      ObjectProvider<SessionMessenger> messengers,
      IngestMetrics metrics,
      int instanceHourCap,
      long appCap,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.messengers = Objects.requireNonNull(messengers, "messengers");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.instanceHourCap = instanceHourCap;
    this.appCap = appCap;
    this.clock = clock;
    this.known = Caffeine.newBuilder().maximumSize(CACHE_KEYS).build();
    this.overflowed = Caffeine.newBuilder().maximumSize(CACHE_KEYS).build();
    Gauge.builder("argos.dictionary.unresolved", unresolved, Set::size)
        .description("Dictionary ids still unknown after the requests to the agents (11 §7)")
        .register(metrics.registry());
  }

  /** Starts the {@code argos-dict-writer} thread. */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    Thread t = new Thread(this::run, "argos-dict-writer");
    t.setDaemon(true);
    thread = t;
    t.start();
  }

  // --- decoder side -------------------------------------------------------------------------

  /**
   * The id to store for {@code id} of {@code kind} referenced by a row of {@code instanceId}:
   * {@link #OVERFLOW_ID} for an overflowed key, otherwise {@code id} itself; an unknown id is
   * scheduled for lookup. Non-blocking.
   */
  public long resolve(int appId, int instanceId, DictionaryKind kind, long id) {
    if (id == 0) {
      return 0;
    }
    Key key = new Key(appId, kind, id);
    if (overflowed.getIfPresent(key) != null) {
      return OVERFLOW_ID;
    }
    if (known.getIfPresent(key) == null && id != OVERFLOW_ID) {
      noteUnknown(key, instanceId);
    }
    return id;
  }

  /**
   * Makes sure the server-made entry {@code text} of {@code kind} exists (ADR-0010 C-143 (6): the
   * {@code APP_VERSION} key of a session's version). Not subject to the caps. Non-blocking.
   */
  public void ensureServerEntry(int appId, DictionaryKind kind, long id, String text) {
    if (id == 0 || known.getIfPresent(new Key(appId, kind, id)) != null) {
      return;
    }
    DictionaryEntry entry =
        DictionaryEntry.newBuilder().setKind(kind).setHash(id).setText(text).build();
    if (!incoming.offer(new Incoming(appId, 0, entry, true))) {
      metrics.error("dictionary_queue");
    }
  }

  /** Whether {@code (appId, kind, id)} is known to be stored. */
  public boolean isKnown(int appId, DictionaryKind kind, long id) {
    return known.getIfPresent(new Key(appId, kind, id)) != null;
  }

  /** Whether {@code (appId, kind, id)} was mapped to {@link #OVERFLOW_ID}. */
  public boolean isOverflowed(int appId, DictionaryKind kind, long id) {
    return overflowed.getIfPresent(new Key(appId, kind, id)) != null;
  }

  /** Ids given up (the gauge {@code argos_dictionary_unresolved}). */
  public int unresolvedCount() {
    return unresolved.size();
  }

  /** Entries waiting for the writer thread (tests). */
  public int queuedEntries() {
    return incoming.size() + retry.size();
  }

  private void noteUnknown(Key key, int instanceId) {
    if (unknown.containsKey(key) || unresolved.contains(key)) {
      return;
    }
    if (unknown.size() >= MAX_UNKNOWN) {
      metrics.error("dictionary_unknown_full");
      return;
    }
    unknown.putIfAbsent(key, new Unknown(instanceId, clock.millis()));
  }

  // --- DictionaryIntake ---------------------------------------------------------------------

  @Override
  public void onDictionaryEntries(int appId, int instanceId, List<DictionaryEntry> entries) {
    for (DictionaryEntry entry : entries) {
      if (entry.getHash() == 0 || DictKinds.pgName(entry.getKind()).isEmpty()) {
        metrics.error("dictionary_entry");
        continue;
      }
      if (!incoming.offer(new Incoming(appId, instanceId, entry, false))) {
        metrics.error("dictionary_queue");
      }
    }
  }

  // --- argos-dict-writer --------------------------------------------------------------------

  private void run() {
    long nextRequests = clock.millis();
    List<Incoming> batch = new ArrayList<>(BATCH_ROWS);
    while (!stopping || !incoming.isEmpty()) {
      try {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_BATCH_MS);
        while (batch.size() < BATCH_ROWS) {
          long left = deadline - System.nanoTime();
          Incoming next = left > 0 ? incoming.poll(left, TimeUnit.NANOSECONDS) : incoming.poll();
          if (next == null) {
            break;
          }
          batch.add(next);
        }
        if (!batch.isEmpty() || !retry.isEmpty()) {
          processIncoming(batch);
          batch.clear();
        }
        long now = clock.millis();
        if (now >= nextRequests) {
          processUnknown(now);
          nextRequests = now + REQUEST_BATCH_MS;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        LOG.warn("Dictionary writer step failed: {}", e.toString());
        batch.clear();
      }
    }
  }

  /**
   * Processes everything queued and the due lookups on the calling thread: what one turn of {@code
   * argos-dict-writer} does (tests drive the resolver this way without the thread).
   */
  void drainNow() {
    List<Incoming> batch = new ArrayList<>();
    incoming.drainTo(batch);
    processIncoming(batch);
    processUnknown(clock.millis());
  }

  private void processIncoming(List<Incoming> batch) {
    long now = clock.millis();
    List<IngestStore.DictionaryRow> rows = new ArrayList<>(retry);
    List<Key> rowKeys = new ArrayList<>(retryKeys);
    retry.clear();
    retryKeys.clear();
    // Group the entries that are not known yet by (app, kind) for one existence query each.
    Map<Key, Incoming> candidates = new LinkedHashMap<>();
    for (Incoming in : batch) {
      Key key = new Key(in.appId(), in.entry().getKind(), in.entry().getHash());
      if (known.getIfPresent(key) != null) {
        if (!in.serverEntry()) {
          rows.add(row(in)); // refresh last_seen
          rowKeys.add(key);
        }
        continue;
      }
      candidates.putIfAbsent(key, in);
    }
    Map<String, Set<Long>> existing = new HashMap<>();
    Map<String, List<Long>> byGroup = new LinkedHashMap<>();
    for (Key key : candidates.keySet()) {
      byGroup
          .computeIfAbsent(key.appId() + "/" + key.kind().getNumber(), k -> new ArrayList<>())
          .add(key.hash());
    }
    try {
      for (Map.Entry<String, List<Long>> group : byGroup.entrySet()) {
        String[] parts = group.getKey().split("/", 2);
        int appId = Integer.parseInt(parts[0]);
        DictionaryKind kind = DictionaryKind.forNumber(Integer.parseInt(parts[1]));
        existing.put(
            group.getKey(),
            store.existingDictionary(
                appId, DictKinds.pgName(kind).orElseThrow(), group.getValue()));
      }
    } catch (Exception e) {
      LOG.warn(
          "Dictionary lookup in PostgreSQL failed, entries kept for a retry: {}", e.toString());
      requeue(candidates.values());
      flush(rows, rowKeys);
      return;
    }
    for (Map.Entry<Key, Incoming> c : candidates.entrySet()) {
      Key key = c.getKey();
      Incoming in = c.getValue();
      Set<Long> found = existing.getOrDefault(key.appId() + "/" + key.kind().getNumber(), Set.of());
      if (found.contains(key.hash()) || in.serverEntry()) {
        rows.add(row(in));
        rowKeys.add(key);
        continue;
      }
      if (!withinCaps(in, now)) {
        overflowed.put(key, Boolean.TRUE);
        unknown.remove(key);
        unresolved.remove(key);
        metrics.overflow(1);
        Key overflowKey = new Key(key.appId(), key.kind(), OVERFLOW_ID);
        if (known.getIfPresent(overflowKey) == null) {
          rows.add(
              new IngestStore.DictionaryRow(
                  key.appId(), pgName(key.kind()), OVERFLOW_ID, OVERFLOW_TEXT, false, null));
          rowKeys.add(overflowKey);
        }
        continue;
      }
      rows.add(row(in));
      rowKeys.add(key);
    }
    flush(rows, rowKeys);
  }

  private void requeue(Iterable<Incoming> entries) {
    for (Incoming in : entries) {
      if (!incoming.offer(in)) {
        metrics.error("dictionary_queue");
      }
    }
  }

  private void flush(List<IngestStore.DictionaryRow> rows, List<Key> keys) {
    if (rows.isEmpty()) {
      return;
    }
    try {
      store.upsertDictionary(rows);
      for (Key key : keys) {
        known.put(key, Boolean.TRUE);
        unknown.remove(key);
        unresolved.remove(key);
      }
    } catch (Exception e) {
      LOG.warn("Dictionary upsert of {} rows failed, retried: {}", rows.size(), e.toString());
      if (retry.size() + rows.size() > MAX_QUEUED_ENTRIES) {
        metrics.error("dictionary_store");
        return;
      }
      retry.addAll(rows);
      retryKeys.addAll(keys);
    }
  }

  private boolean withinCaps(Incoming in, long now) {
    long hour = Math.floorDiv(now, TimeUnit.HOURS.toMillis(1));
    long[] counter = instanceHour.computeIfAbsent(in.instanceId(), i -> new long[] {hour, 0});
    if (counter[0] != hour) {
      counter[0] = hour;
      counter[1] = 0;
    }
    if (counter[1] >= instanceHourCap) {
      return false;
    }
    Long total = appTotals.get(in.appId());
    if (total == null) {
      try {
        total = store.dictionaryCount(in.appId());
      } catch (Exception e) {
        total = 0L;
      }
    }
    if (total >= appCap) {
      appTotals.put(in.appId(), total);
      return false;
    }
    counter[1]++;
    appTotals.put(in.appId(), total + 1);
    if (instanceHour.size() > 100_000) {
      instanceHour.entrySet().removeIf(e -> e.getValue()[0] != hour);
    }
    return true;
  }

  private void processUnknown(long now) {
    if (unknown.isEmpty()) {
      return;
    }
    List<Map.Entry<Key, Unknown>> due = new ArrayList<>();
    for (Map.Entry<Key, Unknown> e : unknown.entrySet()) {
      if (e.getValue().nextAttemptMs <= now) {
        due.add(e);
      }
    }
    if (due.isEmpty()) {
      return;
    }
    // First attempt: the id may simply not be cached (restart, eviction).
    Map<String, List<Map.Entry<Key, Unknown>>> unchecked = new LinkedHashMap<>();
    for (Map.Entry<Key, Unknown> e : due) {
      if (!e.getValue().checkedStore && DictKinds.pgName(e.getKey().kind()).isPresent()) {
        unchecked
            .computeIfAbsent(
                e.getKey().appId() + "/" + e.getKey().kind().getNumber(), k -> new ArrayList<>())
            .add(e);
      }
    }
    for (List<Map.Entry<Key, Unknown>> group : unchecked.values()) {
      Key first = group.get(0).getKey();
      List<Long> hashes = group.stream().map(e -> e.getKey().hash()).toList();
      try {
        Set<Long> found = store.existingDictionary(first.appId(), pgName(first.kind()), hashes);
        for (Map.Entry<Key, Unknown> e : group) {
          e.getValue().checkedStore = true;
          if (found.contains(e.getKey().hash())) {
            known.put(e.getKey(), Boolean.TRUE);
            unknown.remove(e.getKey());
          }
        }
      } catch (Exception ex) {
        LOG.debug("Dictionary lookup failed, retried: {}", ex.toString());
        return;
      }
    }
    // Ask the agents.
    Map<Long, List<Map.Entry<Key, Unknown>>> byInstance = new LinkedHashMap<>();
    for (Map.Entry<Key, Unknown> e : due) {
      if (unknown.containsKey(e.getKey())) {
        long route = ((long) e.getKey().appId() << 32) | (e.getValue().instanceId & 0xFFFF_FFFFL);
        byInstance.computeIfAbsent(route, r -> new ArrayList<>()).add(e);
      }
    }
    SessionMessenger messenger = messengers.getIfAvailable();
    for (Map.Entry<Long, List<Map.Entry<Key, Unknown>>> route : byInstance.entrySet()) {
      int appId = (int) (route.getKey() >>> 32);
      int instanceId = (int) (long) route.getKey();
      List<Map.Entry<Key, Unknown>> items = route.getValue();
      for (int from = 0; from < items.size(); from += MAX_REFS_PER_REQUEST) {
        List<Map.Entry<Key, Unknown>> part =
            items.subList(from, Math.min(items.size(), from + MAX_REFS_PER_REQUEST));
        DictionaryRequest.Builder request = DictionaryRequest.newBuilder();
        for (Map.Entry<Key, Unknown> e : part) {
          request.addRefs(
              DictionaryRef.newBuilder().setKind(e.getKey().kind()).setHash(e.getKey().hash()));
        }
        boolean sent =
            messenger != null && messenger.requestDictionary(appId, instanceId, request.build());
        for (Map.Entry<Key, Unknown> e : part) {
          Unknown u = e.getValue();
          if (sent) {
            u.attempts++;
          }
          u.nextAttemptMs = now + ATTEMPT_SPACING_MS;
          if (u.attempts >= MAX_ATTEMPTS || now - u.firstSeenMs > UNKNOWN_TTL_MS) {
            unknown.remove(e.getKey());
            if (unresolved.size() < MAX_UNKNOWN) {
              unresolved.add(e.getKey());
            }
          }
        }
      }
    }
  }

  private static IngestStore.DictionaryRow row(Incoming in) {
    DictionaryEntry entry = in.entry();
    String text = sanitize(entry.getText());
    boolean truncated = entry.getTruncated();
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    if (utf8.length > MAX_TEXT_BYTES) {
      text = cutUtf8(text, MAX_TEXT_BYTES);
      truncated = true;
    }
    Integer norm =
        entry.getKind() == DictionaryKind.SQL_TEXT && entry.getNormVersion() != 0
            ? Integer.valueOf(entry.getNormVersion())
            : null;
    return new IngestStore.DictionaryRow(
        in.appId(), pgName(entry.getKind()), entry.getHash(), text, truncated, norm);
  }

  private static String pgName(DictionaryKind kind) {
    Optional<String> name = DictKinds.pgName(kind);
    return name.orElseThrow(() -> new IllegalArgumentException("No dict_kind for " + kind));
  }

  /** NUL characters cannot be stored in PostgreSQL text: replaced by U+FFFD. */
  static String sanitize(String text) {
    return text.indexOf('\0') < 0 ? text : text.replace('\0', '�');
  }

  /** The longest prefix of {@code text} with at most {@code maxBytes} UTF-8 bytes. */
  static String cutUtf8(String text, int maxBytes) {
    int bytes = 0;
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (bytes + n > maxBytes) {
        break;
      }
      bytes += n;
      i += Character.charCount(cp);
    }
    return text.substring(0, i);
  }

  /** Stops the writer thread after it stored what is queued (at most 5 s). */
  public void close() {
    stopping = true;
    Thread t = thread;
    if (t != null) {
      try {
        t.join(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      t.interrupt();
    }
  }

  @Override
  public void destroy() {
    close();
  }
}
