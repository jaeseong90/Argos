/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.CodedOutputStream;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.trace.Dictionary;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * The dictionary exchange of 11 §7 on the agent side (10 §10.4 {@code DictionaryCache}, ADR-0010
 * C-105):
 *
 * <ul>
 *   <li>Pre-send: while the sender builds a batch it {@link #reference references} every dictionary
 *       id the batch carries; ids whose text the {@link Dictionary} keeps and that were not sent
 *       before become {@code DictionaryEntry} messages that {@link #takePending()} returns in
 *       groups of at most {@value ProtocolLimits#MAX_DICTIONARY_ENTRIES_PER_MESSAGE} entries and at
 *       most the batch target of 11 §2 serialized (1 MiB, never above the server's {@code
 *       max_message_bytes}: entries carry up to 64 KiB of text, so the count alone could pass the
 *       server's 4 MiB inbound limit and end the session stream), to be written on the session
 *       stream before the batch's RPC starts. A cache hit sends nothing.
 *   <li>Memory of what was sent: one {@link LongFifoSet} per kind with the 10 §10.4 sizes ({@link
 *       Dictionary#capacity(int)}: service 10,000, SQL 10,000, class and method 20,000, host 1,000,
 *       thread 1,000, frame 50,000; the other kinds as the dictionary sizes them). A forgotten id
 *       is simply sent again (the server upserts, 11 §7 step 2).
 *   <li>{@code DictionaryRequest}: {@link #answer} returns the entries whose text is still kept and
 *       silently skips the others (11 §7 step 3: the server marks them unresolved after 3 tries),
 *       in one message within the same byte bound; refs beyond it wait for the server's next
 *       request.
 *   <li>Returned entries: a {@code DictionaryEntries} message the session could not write (its
 *       bounded pending list dropped it, the session ended) comes back through {@link #returned}
 *       (any thread); its ids are forgotten as sent on the sender thread, so the next batch that
 *       refers to them sends them again. At most {@value #MAX_RETURNED_ENTRIES} returned entries
 *       wait; beyond that the server's {@code DictionaryRequest} recovers them (11 §7 step 3).
 * </ul>
 *
 * The kind of an entry is the kind its text was first kept for ({@link Dictionary#kind(long)}, C-16
 * numbers, which equal the protocol's {@code DictionaryKind}); the reference's kind is only a
 * fallback. Texts are cut to 64 KiB ({@code SQL_TEXT}) or 8 KiB UTF-8 bytes, with {@code truncated}
 * set (also for texts the dictionary itself cut). {@code SQL_TEXT} carries {@code norm_version}
 * {@value #NORM_VERSION} (D-22; the normalizer has one version so far).
 *
 * <p>Threads: {@link #reference} and {@link #takePending} on {@code argos-sender} only; {@link
 * #answer} on {@code argos-io}, reading the concurrent {@link Dictionary} only; {@link #returned}
 * on any thread (a lock-free bounded inbox).
 */
final class DictionaryCache {
  /** {@code DictionaryEntry.norm_version} of {@code SQL_TEXT} (D-22). */
  static final int NORM_VERSION = 1;

  /** Field number of {@code DictionaryEntries.entries}. */
  static final int ENTRIES_FIELD = DictionaryEntries.ENTRIES_FIELD_NUMBER;

  /** Room for the {@code AgentMessage} tag and length around a {@code DictionaryEntries}. */
  static final int WRAPPER_RESERVE_BYTES = 16;

  /** Returned entries waiting for the sender thread (16 messages of 1,000 entries). */
  static final int MAX_RETURNED_ENTRIES = 16 * ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE;

  /** The batch target of 11 §2 without a session's {@code Limits}. */
  static final IntSupplier DEFAULT_MESSAGE_BYTES =
      new IntSupplier() {
        @Override
        public int getAsInt() {
          return ProtocolLimits.TARGET_BATCH_BYTES;
        }
      };

  private final Dictionary dictionary;
  private final IntSupplier maxMessageBytes;
  private final LongFifoSet[] sent = new LongFifoSet[DictKind.COUNT];
  private final LongFifoSet pending =
      new LongFifoSet(ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE * 64);
  private final List<DictionaryEntry> pendingEntries = new ArrayList<DictionaryEntry>();
  private final ConcurrentLinkedQueue<long[]> returned = new ConcurrentLinkedQueue<long[]>();
  private final AtomicInteger returnedEntries = new AtomicInteger();

  DictionaryCache(Dictionary dictionary) {
    this(dictionary, DEFAULT_MESSAGE_BYTES);
  }

  /**
   * @param maxMessageBytes the serialized size one {@code DictionaryEntries} message stays at or
   *     under ({@code BatchAssembler.targetBatchBytes()}: 1 MiB, 11 §2)
   */
  DictionaryCache(Dictionary dictionary, IntSupplier maxMessageBytes) {
    this.dictionary = dictionary;
    this.maxMessageBytes = maxMessageBytes;
  }

  /**
   * Notes that a batch carries dictionary id {@code id} of kind {@code kindHint} (0 and unknown ids
   * are ignored). Sender thread.
   */
  void reference(int kindHint, long id) {
    if (returnedEntries.get() != 0) {
      applyReturned();
    }
    if (id == 0 || pending.contains(id)) {
      return;
    }
    int kind = dictionary.kind(id);
    if (kind == 0) {
      // The text is not kept (dictionary full): nothing can be sent; the server shows the id.
      return;
    }
    LongFifoSet memory = memory(kind > 0 && kind < DictKind.COUNT ? kind : kindHint);
    if (memory == null || memory.contains(id)) {
      return;
    }
    DictionaryEntry entry = entry(kind > 0 ? kind : kindHint, id);
    if (entry == null) {
      return;
    }
    pending.add(id);
    pendingEntries.add(entry);
  }

  /** True when {@link #takePending()} has entries. */
  boolean hasPending() {
    return !pendingEntries.isEmpty();
  }

  /**
   * The entries collected since the last call, in messages of at most 1,000 entries and at most
   * {@link #messageBytes()} serialized (11 §2), and remembers them as sent. An entry that would
   * pass the byte bound starts the next message; one entry (at most 64 KiB of text plus its fields)
   * always fits. Sender thread.
   */
  List<DictionaryEntries> takePending() {
    if (pendingEntries.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    int cap = messageBytes();
    List<DictionaryEntries> messages = new ArrayList<DictionaryEntries>();
    DictionaryEntries.Builder message = DictionaryEntries.newBuilder();
    int size = 0;
    for (DictionaryEntry entry : pendingEntries) {
      LongFifoSet memory = memory(entry.getKindValue());
      if (memory != null) {
        memory.add(entry.getHash());
      }
      int entrySize = CodedOutputStream.computeMessageSize(ENTRIES_FIELD, entry);
      if (message.getEntriesCount() > 0
          && (message.getEntriesCount() >= ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE
              || size + entrySize > cap)) {
        messages.add(message.build());
        message = DictionaryEntries.newBuilder();
        size = 0;
      }
      message.addEntries(entry);
      size += entrySize;
    }
    if (message.getEntriesCount() > 0) {
      messages.add(message.build());
    }
    pendingEntries.clear();
    pending.clear();
    return java.util.Collections.unmodifiableList(messages);
  }

  /**
   * Forgets entries that were taken but could not be written (the session ended before), so that
   * the next batch sends them again. Sender thread.
   */
  void unsent(List<DictionaryEntries> messages) {
    for (DictionaryEntries message : messages) {
      for (DictionaryEntry entry : message.getEntriesList()) {
        LongFifoSet memory = memory(entry.getKindValue());
        if (memory != null) {
          memory.remove(entry.getHash());
        }
      }
    }
  }

  /**
   * The answer to {@code request}: the requested entries whose text is kept (at most 1,000 and at
   * most {@link #messageBytes()} serialized, 11 §2), or null when none is (no answer is sent, 11
   * §7). Refs beyond the byte bound are left to the server's next {@code DictionaryRequest} (11 §7
   * step 3). Any thread.
   */
  DictionaryEntries answer(DictionaryRequest request) {
    DictionaryEntries.Builder answer = DictionaryEntries.newBuilder();
    int limit = ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE;
    int cap = messageBytes();
    int size = 0;
    for (DictionaryRef ref : request.getRefsList()) {
      if (answer.getEntriesCount() >= limit) {
        break;
      }
      int kind = dictionary.kind(ref.getHash());
      DictionaryEntry entry = entry(kind != 0 ? kind : ref.getKindValue(), ref.getHash());
      if (entry != null) {
        int entrySize = CodedOutputStream.computeMessageSize(ENTRIES_FIELD, entry);
        if (answer.getEntriesCount() > 0 && size + entrySize > cap) {
          break;
        }
        answer.addEntries(entry);
        size += entrySize;
      }
    }
    return answer.getEntriesCount() == 0 ? null : answer.build();
  }

  /**
   * The serialized size a {@code DictionaryEntries} message stays at or under: the batch target (1
   * MiB, 11 §2) less room for the {@code AgentMessage} around it.
   */
  int messageBytes() {
    return Math.max(1, maxMessageBytes.getAsInt() - WRAPPER_RESERVE_BYTES);
  }

  /**
   * A message the session could not write (dropped from its bounded pending list, or lost with the
   * session): its ids are forgotten as sent on the sender thread, so the next batch that refers to
   * them sends them again. Any thread, lock-free; beyond {@value #MAX_RETURNED_ENTRIES} waiting
   * entries the message is ignored and the server's {@code DictionaryRequest} recovers it (11 §7).
   */
  void returned(DictionaryEntries message) {
    int count = message.getEntriesCount();
    if (count == 0) {
      return;
    }
    if (returnedEntries.addAndGet(count) > MAX_RETURNED_ENTRIES) {
      returnedEntries.addAndGet(-count);
      return;
    }
    long[] pairs = new long[count * 2];
    for (int i = 0; i < count; i++) {
      DictionaryEntry entry = message.getEntries(i);
      pairs[2 * i] = entry.getKindValue();
      pairs[2 * i + 1] = entry.getHash();
    }
    returned.offer(pairs);
  }

  /** Forgets the returned entries as sent (sender thread). */
  private void applyReturned() {
    long[] pairs;
    while ((pairs = returned.poll()) != null) {
      returnedEntries.addAndGet(-pairs.length / 2);
      for (int i = 0; i + 1 < pairs.length; i += 2) {
        LongFifoSet memory = memory((int) pairs[i]);
        if (memory != null) {
          memory.remove(pairs[i + 1]);
        }
      }
    }
  }

  /** The entry of {@code id} with its text cut to the protocol cap, or null without a text. */
  private DictionaryEntry entry(int kind, long id) {
    String text = dictionary.text(id);
    if (text == null || kind <= 0 || kind > DictionaryKind.POOL_VALUE) {
      return null;
    }
    int cap =
        kind == DictKind.SQL
            ? ProtocolLimits.MAX_SQL_TEXT_BYTES
            : ProtocolLimits.MAX_DICTIONARY_TEXT_BYTES;
    String cut = Utf8.cut(text, cap);
    DictionaryEntry.Builder entry =
        DictionaryEntry.newBuilder()
            .setKindValue(kind)
            .setHash(id)
            .setText(cut)
            .setTruncated(dictionary.truncated(id) || cut.length() < text.length());
    if (kind == DictKind.SQL) {
      entry.setNormVersion(NORM_VERSION);
    }
    return entry.build();
  }

  private LongFifoSet memory(int kind) {
    if (kind <= 0 || kind >= DictKind.COUNT) {
      return null;
    }
    LongFifoSet set = sent[kind];
    if (set == null) {
      set = new LongFifoSet(Dictionary.capacity(kind));
      sent[kind] = set;
    }
    return set;
  }
}
