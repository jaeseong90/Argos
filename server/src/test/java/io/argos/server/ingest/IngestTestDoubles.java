/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import com.google.protobuf.Message;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.SessionClose;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** In-memory collaborators of the ingest unit tests. */
final class IngestTestDoubles {

  private IngestTestDoubles() {}

  /** An {@link IngestStore} in memory. */
  static final class MemoryStore implements IngestStore {
    final Map<String, DictionaryRow> dictionary = new ConcurrentHashMap<>();
    final List<ErrorGroupRow> errorGroups = new CopyOnWriteArrayList<>();
    final Map<Integer, Integer> apdex = new ConcurrentHashMap<>();
    volatile boolean failing;

    static String key(int appId, String kind, long hash) {
      return appId + "/" + kind + "/" + hash;
    }

    @Override
    public void upsertDictionary(List<DictionaryRow> rows) throws Exception {
      if (failing) {
        throw new Exception("store down");
      }
      for (DictionaryRow row : rows) {
        dictionary.putIfAbsent(key(row.appId(), row.kind(), row.hash()), row);
      }
    }

    @Override
    public Set<Long> existingDictionary(int appId, String kind, Collection<Long> hashes)
        throws Exception {
      if (failing) {
        throw new Exception("store down");
      }
      Set<Long> found = new HashSet<>();
      for (long h : hashes) {
        if (dictionary.containsKey(key(appId, kind, h))) {
          found.add(h);
        }
      }
      return found;
    }

    @Override
    public long dictionaryCount(int appId) {
      return dictionary.values().stream().filter(r -> r.appId() == appId).count();
    }

    @Override
    public void upsertErrorGroups(List<ErrorGroupRow> rows) {
      errorGroups.addAll(rows);
    }

    @Override
    public Map<Integer, Integer> apdexByApp() {
      return new HashMap<>(apdex);
    }
  }

  /** A messenger that records the requests; {@code live} decides whether a session exists. */
  static final class RecordingMessenger implements SessionMessenger {
    final List<DictionaryRequest> requests = new CopyOnWriteArrayList<>();
    volatile boolean live = true;

    @Override
    public boolean requestDictionary(int appId, int instanceId, DictionaryRequest request) {
      if (!live) {
        return false;
      }
      requests.add(request);
      return true;
    }

    @Override
    public boolean close(int instanceId, SessionClose close) {
      return false;
    }
  }

  static ObjectProvider<SessionMessenger> provider(SessionMessenger messenger) {
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("messenger", messenger);
    return factory.getBeanProvider(SessionMessenger.class);
  }

  /** A session context of app 7, instance 3, env prod. */
  static SessionContext session(
      IpMode ipMode, UserIdMode userIdMode, long receivedMs, long correctionMs, String version) {
    return new SessionContext(
        7,
        3,
        "prod",
        UUID.fromString("00000000-0000-0000-0000-000000000042"),
        99L,
        ipMode,
        userIdMode,
        400,
        receivedMs,
        version,
        correctionMs);
  }

  /** A WAL record carrying {@code batch} received in {@code session}. */
  static WalRecord record(WalRecordType type, SessionContext session, Message batch, long seq) {
    byte[] payload = WalDataPayload.of(session, batch.toByteString()).encode();
    return new WalRecord(
        new WalPosition(0, 16 + seq * 1000, 45 + payload.length),
        type,
        session.instanceId(),
        session.agentEpoch(),
        seq,
        session.receivedAtMs(),
        session.tsCorrectionMs(),
        payload);
  }

  /** A decoder over in-memory collaborators. */
  static RecordDecoder decoder(MemoryStore store, IngestMetrics metrics) {
    DictionaryResolver resolver =
        new DictionaryResolver(
            store,
            provider(new RecordingMessenger()),
            metrics,
            10_000,
            1_000_000,
            Clock.systemUTC());
    return new RecordDecoder(resolver, new ErrorGroupRecorder(store, metrics), metrics);
  }

  static List<Object> list(Object... values) {
    List<Object> list = new ArrayList<>();
    for (Object v : values) {
      list.add(v);
    }
    return list;
  }
}
