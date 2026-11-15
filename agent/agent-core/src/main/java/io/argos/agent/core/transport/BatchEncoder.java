/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.v1.DictionaryEntries;
import java.util.List;

/**
 * The sender's encoding without a network: the same {@link BatchAssembler} and {@link
 * DictionaryCache}, serialized and thrown away. It is what {@code test.exporter=noop} measures (18
 * §5.4 "인코딩까지 하고 버림", the instrumentation and encoding cost of the overhead bench, 01 §7.3). One
 * thread at a time ({@code argos-sender}).
 */
public final class BatchEncoder {
  private final BatchAssembler assembler;
  private final DictionaryCache dictionary;

  public BatchEncoder(Dictionary dictionary) {
    this.dictionary = new DictionaryCache(dictionary);
    this.assembler = new BatchAssembler(dictionary, this.dictionary);
  }

  /** Encodes {@code tx} and the dictionary entries it is the first to use; returns the bytes. */
  public int encode(Transaction tx) {
    return assembler.record(tx).toByteArray().length + encodeDictionary();
  }

  /**
   * Encodes {@code profile} and the dictionary entries it is the first to use; returns the bytes.
   */
  public int encode(ProfileRecord profile) {
    return assembler.profile(profile).toByteArray().length + encodeDictionary();
  }

  private int encodeDictionary() {
    if (!dictionary.hasPending()) {
      return 0;
    }
    int bytes = 0;
    List<DictionaryEntries> messages = dictionary.takePending();
    for (DictionaryEntries message : messages) {
      bytes += message.toByteArray().length;
    }
    return bytes;
  }
}
