/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.sender;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.transportpoc.proto.BatchHeader;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.argos.agent.transportpoc.proto.TransactionKind;
import io.argos.agent.transportpoc.proto.TransactionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the small {@code TransactionBatch} of the comparison sender (11 §4.3 messages): up to
 * {@link PocSettings#RECORDS_PER_BATCH} records from ended transactions, topped up with synthetic
 * records so that every batch has the same shape. Only the {@code argos-sender} thread uses it.
 */
public final class BatchFactory {
  private static final int MAX_PENDING = 500;

  private final int instanceId;
  private final long agentEpoch = ThreadLocalRandom.current().nextLong();
  private final List<Transaction> pending = new ArrayList<Transaction>();
  private long seq;
  private long syntheticTx;

  public BatchFactory(int instanceId) {
    this.instanceId = instanceId;
  }

  /** Keeps ended transactions for the next batch (bounded; the rest is dropped). */
  public void add(List<Transaction> transactions) {
    for (Transaction transaction : transactions) {
      if (pending.size() >= MAX_PENDING) {
        return;
      }
      pending.add(transaction);
    }
  }

  /** The next batch: header with a new seq and {@link PocSettings#RECORDS_PER_BATCH} records. */
  public TransactionBatch next() {
    long now = System.currentTimeMillis();
    TransactionBatch.Builder batch =
        TransactionBatch.newBuilder()
            .setHeader(
                BatchHeader.newBuilder()
                    .setInstanceId(instanceId)
                    .setAgentEpoch(agentEpoch)
                    .setSeq(++seq)
                    .setSentAtMs(now));
    int records = 0;
    while (records < PocSettings.RECORDS_PER_BATCH && !pending.isEmpty()) {
      batch.addRecords(record(pending.remove(0)));
      records++;
    }
    while (records < PocSettings.RECORDS_PER_BATCH) {
      batch.addRecords(synthetic(now));
      records++;
    }
    return batch.build();
  }

  private static TransactionRecord record(Transaction transaction) {
    TraceContext context = transaction.context();
    TransactionRecord.Builder record =
        TransactionRecord.newBuilder()
            .setTxId(context.txId)
            .setKind(TransactionKind.HTTP)
            .setStartTsMs(context.startEpochMs)
            .setElapsedUs(transaction.elapsedNanos() / 1_000L)
            .setHttpStatus(context.status)
            .setError(context.error);
    if (transaction.httpMethod() != null) {
      record.setHttpMethod(transaction.httpMethod());
    }
    if (transaction.path() != null) {
      record.setUrlPath(transaction.path());
    }
    if (context.serviceName != null) {
      record.setServiceId(Dictionary.hash64(context.serviceName));
    }
    return record.build();
  }

  private TransactionRecord synthetic(long now) {
    long id = ++syntheticTx;
    return TransactionRecord.newBuilder()
        .setTxId((now << 20) | (id & 0xFFFFF))
        .setKind(TransactionKind.HTTP)
        .setServiceId(Dictionary.hash64("GET /poc/{id}"))
        .setStartTsMs(now - 12)
        .setElapsedUs(12_345L + id % 1000)
        .setHttpMethod("GET")
        .setHttpStatus(200)
        .setUrlPath("/poc/" + id)
        .setCpuTimeUs(4_321L)
        .setSqlCount(2)
        .setSqlTimeMs(3)
        .build();
  }

  /** Last batch sequence number handed out. */
  public long seq() {
    return seq;
  }
}
