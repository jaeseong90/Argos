/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.trace.Dictionary;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The byte bound of the dictionary messages (11 §2: the agent aims at 1 MiB per message, the server
 * takes at most 4 MiB inbound; 11 §7): {@code DictionaryEntries} carry up to 64 KiB of SQL text per
 * entry, so 1,000 entries could pass both limits and end the session stream.
 */
@Tag("req:DSN-02-11")
@Tag("req:SEC-11")
class DictionaryCacheBytesTest {
  /**
   * SQL texts of 64 KiB: the dictionary keeps about 60 of them within its own 8 MiB budget (2 bytes
   * per character), several times the 1 MiB message target.
   */
  private static final int SQL_COUNT = 60;

  private final Dictionary dictionary = new Dictionary();

  private static String sql(int index) {
    char[] text = new char[ProtocolLimits.MAX_SQL_TEXT_BYTES];
    Arrays.fill(text, 'x');
    String prefix = "select /* " + index + " */ ";
    prefix.getChars(0, prefix.length(), text, 0);
    return new String(text);
  }

  private List<Long> referenceSql(DictionaryCache cache) {
    List<Long> ids = new ArrayList<Long>();
    for (int i = 0; i < SQL_COUNT; i++) {
      long id = dictionary.id(DictKind.SQL, sql(i));
      assertThat(dictionary.text(id)).as("kept by the dictionary").isNotNull();
      cache.reference(DictKind.SQL, id);
      ids.add(id);
    }
    return ids;
  }

  private static Set<Long> hashes(List<DictionaryEntries> messages) {
    Set<Long> hashes = new HashSet<Long>();
    for (DictionaryEntries message : messages) {
      for (DictionaryEntry entry : message.getEntriesList()) {
        hashes.add(entry.getHash());
      }
    }
    return hashes;
  }

  @Test
  @DisplayName(
      "11 §2, §7: pre-sent dictionary messages of 64 KiB SQL texts stay at or under the 1 MiB"
          + " batch target each, and no entry is lost")
  void takePendingByBytes() {
    DictionaryCache cache = new DictionaryCache(dictionary);
    List<Long> ids = referenceSql(cache);
    List<DictionaryEntries> messages = cache.takePending();

    assertThat(messages).as("several messages, not one of about 4 MiB").hasSizeGreaterThan(3);
    for (DictionaryEntries message : messages) {
      assertThat(message.getSerializedSize())
          .isLessThanOrEqualTo(ProtocolLimits.TARGET_BATCH_BYTES);
      assertThat(message.getEntriesCount())
          .isLessThanOrEqualTo(ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE);
      assertThat(
              io.argos.proto.v1.AgentMessage.newBuilder()
                  .setDictionaryEntries(message)
                  .build()
                  .getSerializedSize())
          .as("with the AgentMessage around it")
          .isLessThanOrEqualTo(ProtocolLimits.TARGET_BATCH_BYTES);
    }
    assertThat(hashes(messages)).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(cache.hasPending()).isFalse();
  }

  @Test
  @DisplayName(
      "11 §2: the bound follows the session's batch target (a server max_message_bytes below"
          + " 1 MiB)")
  void boundFollowsTheBatchTarget() {
    final int target = 256 * 1024;
    DictionaryCache cache =
        new DictionaryCache(
            dictionary,
            new IntSupplier() {
              @Override
              public int getAsInt() {
                return target;
              }
            });
    List<Long> ids = referenceSql(cache);
    List<DictionaryEntries> messages = cache.takePending();
    for (DictionaryEntries message : messages) {
      assertThat(message.getSerializedSize()).isLessThanOrEqualTo(target);
    }
    assertThat(hashes(messages)).containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  @DisplayName(
      "11 §2, §7 step 3: a DictionaryRequest for many 64 KiB texts is answered within 1 MiB; the"
          + " refs left out are answered by the server's next requests, none is lost")
  void answerByBytes() {
    DictionaryCache cache = new DictionaryCache(dictionary);
    List<Long> ids = referenceSql(cache);
    List<Long> asked = new ArrayList<Long>(ids);
    Set<Long> answered = new HashSet<Long>();
    for (int request = 0; request < 20 && !asked.isEmpty(); request++) {
      DictionaryRequest.Builder refs = DictionaryRequest.newBuilder();
      for (long id : asked) {
        refs.addRefs(DictionaryRef.newBuilder().setKind(DictionaryKind.SQL_TEXT).setHash(id));
      }
      DictionaryEntries answer = cache.answer(refs.build());
      assertThat(answer).isNotNull();
      assertThat(answer.getSerializedSize()).isLessThanOrEqualTo(ProtocolLimits.TARGET_BATCH_BYTES);
      for (DictionaryEntry entry : answer.getEntriesList()) {
        answered.add(entry.getHash());
        asked.remove(Long.valueOf(entry.getHash()));
      }
    }
    assertThat(answered).containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  @DisplayName(
      "11 §7: entries of a message the session dropped come back: the next batch that refers to"
          + " them sends them again")
  void returnedEntriesAreSentAgain() {
    DictionaryCache cache = new DictionaryCache(dictionary);
    long id = dictionary.id(DictKind.SERVICE, "GET /orders");
    cache.reference(DictKind.SERVICE, id);
    List<DictionaryEntries> taken = cache.takePending();
    assertThat(taken).hasSize(1);

    cache.reference(DictKind.SERVICE, id);
    assertThat(cache.hasPending()).as("sent before: a cache hit").isFalse();

    cache.returned(taken.get(0));
    cache.reference(DictKind.SERVICE, id);
    assertThat(cache.hasPending()).as("returned: sent again").isTrue();
    assertThat(hashes(cache.takePending())).containsExactly(id);
  }
}
