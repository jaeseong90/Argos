/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.wal.WalFormat;
import io.argos.server.wal.WalSegmentReader;
import io.argos.server.wal.api.WalRecordType;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import org.testcontainers.DockerClientFactory;

/**
 * Shared steps of the end-to-end tests (fake agent → gRPC → WAL → ClickHouse): apps and agent
 * tokens in PostgreSQL (16 §2 hashing, tokens generated at run time), fake agents over TLS with the
 * environment's test CA, the counts that must equal what was sent (D-13: raw table and rollups), a
 * read-only look at the WAL's block records, and the ClickHouse container for {@code docker pause}.
 */
final class E2e {

  /**
   * The rollups whose {@code sum(count)} must equal the transactions sent (T-ACC-13), {@code
   * instance_stat_1h} of ADR-0010 C-212 (1) included.
   */
  static final List<String> TRANSACTION_ROLLUPS =
      List.of(
          "app_stat_1m",
          "app_stat_1h",
          "service_stat_1m",
          "service_stat_1h",
          "instance_stat_1m",
          "instance_stat_1h");

  private E2e() {}

  /** An app (auto-registered like an installation's first one) and a fresh agent token for it. */
  static AgentTokens.IssuedToken issue(ServerTestEnvironment env, String app) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
  }

  /** A fake agent over TLS to the environment's collector (owns its channel). */
  static FakeAgent agent(
      ServerTestEnvironment env,
      String token,
      String app,
      String instance,
      Records records,
      long epoch) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", env.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token)
        .appName(app)
        .instanceName(instance)
        .records(records)
        .agentEpoch(epoch)
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  /** {@code n} HTTP transactions that ended just now. */
  static List<TransactionRecord> transactions(Records records, int n) {
    List<TransactionRecord> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(records.transaction().build());
    }
    return list;
  }

  /** The {@code argos_reader} client. */
  static ClickHouseHttp reader(ServerTestEnvironment env) {
    return env.clickHouse(Account.READER);
  }

  /** A count query; an empty or NULL result is 0. */
  static long count(ServerTestEnvironment env, String sql) {
    String value = reader(env).scalar(sql);
    return value.isBlank() || value.equals("\\N") ? 0 : Long.parseLong(value.strip());
  }

  /**
   * {@code count()} and {@code uniqExact(tx_id)} of {@code xlog} and {@code sum(count)} of every
   * transaction rollup, for one app.
   */
  static Map<String, Long> totals(ServerTestEnvironment env, int appId) {
    Map<String, Long> t = new LinkedHashMap<>();
    t.put("xlog", count(env, "SELECT count() FROM argos.xlog WHERE app_id = " + appId));
    t.put(
        "xlog distinct tx_id",
        count(env, "SELECT uniqExact(tx_id) FROM argos.xlog WHERE app_id = " + appId));
    for (String rollup : TRANSACTION_ROLLUPS) {
      t.put(
          rollup,
          count(env, "SELECT sum(count) FROM argos." + rollup + " WHERE app_id = " + appId));
    }
    return t;
  }

  /** {@link #totals} as every key mapped to {@code sent}. */
  static Map<String, Long> expected(long sent) {
    Map<String, Long> t = new LinkedHashMap<>();
    t.put("xlog", sent);
    t.put("xlog distinct tx_id", sent);
    for (String rollup : TRANSACTION_ROLLUPS) {
      t.put(rollup, sent);
    }
    return t;
  }

  /**
   * Waits until every total of {@code appId} equals {@code sent} (the writer flushes every 2 s and
   * the views fill with the insert) and returns the last totals seen.
   */
  static Map<String, Long> awaitTotals(
      ServerTestEnvironment env, int appId, long sent, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    Map<String, Long> want = expected(sent);
    Map<String, Long> seen = totals(env, appId);
    while (!seen.equals(want) && System.nanoTime() < deadline) {
      Thread.sleep(500);
      seen = totals(env, appId);
    }
    return seen;
  }

  /**
   * The block records of a WAL directory, read without changing it: the tokens of every {@code
   * BLOCK_INTENT} and the tokens of every {@code BLOCK_COMMIT}, in WAL order.
   */
  static BlockRecords blockRecords(Path walDir) throws IOException {
    Map<Integer, Path> segments = new TreeMap<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(walDir, "seg-*.wal")) {
      for (Path f : files) {
        OptionalInt id = WalFormat.segmentId(f.getFileName().toString());
        if (id.isPresent()) {
          segments.put(id.getAsInt(), f);
        }
      }
    }
    Set<String> intents = new LinkedHashSet<>();
    Set<String> commits = new LinkedHashSet<>();
    long[] data = new long[1];
    for (Map.Entry<Integer, Path> s : segments.entrySet()) {
      try (WalSegmentReader reader = WalSegmentReader.open(s.getValue(), s.getKey())) {
        reader.scan(
            WalFormat.SEGMENT_HEADER_BYTES,
            Long.MAX_VALUE,
            record -> {
              if (record.type() == WalRecordType.BLOCK_INTENT) {
                intents.add(BlockIntent.decode(record.payload()).token().value());
              } else if (record.type() == WalRecordType.BLOCK_COMMIT) {
                commits.add(BlockToken.fromCommitPayload(record.payload()).value());
              } else if (record.type().isData()) {
                data[0]++;
              }
            });
      } catch (NoSuchFileException e) {
        // freed by the WAL GC of a running server meanwhile (only fully committed segments)
      }
    }
    return new BlockRecords(List.copyOf(intents), List.copyOf(commits), data[0], segments.size());
  }

  /**
   * Block records of a WAL.
   *
   * @param intents tokens of the intents
   * @param commits tokens of the commits
   * @param dataRecords data records still in the WAL
   * @param segments segment files
   */
  record BlockRecords(List<String> intents, List<String> commits, long dataRecords, int segments) {

    /** Intents without a commit (what recovery sends again with the same token). */
    List<String> uncommitted() {
      List<String> open = new ArrayList<>(intents);
      open.removeAll(commits);
      return open;
    }
  }

  /** The id of the environment's ClickHouse container (the one publishing its HTTP port). */
  static String clickHouseContainer(ServerTestEnvironment env) {
    int port = URI.create(env.clickHouseUrl()).getPort();
    DockerClient docker = DockerClientFactory.instance().client();
    for (Container c : docker.listContainersCmd().exec()) {
      for (ContainerPort p : c.getPorts()) {
        if (p.getPublicPort() != null && p.getPublicPort() == port) {
          return c.getId();
        }
      }
    }
    throw new IllegalStateException("No container publishes port " + port);
  }

  /** {@code docker pause} of the environment's ClickHouse. */
  static void pauseClickHouse(String containerId) {
    DockerClientFactory.instance().client().pauseContainerCmd(containerId).exec();
  }

  /** {@code docker unpause} of the environment's ClickHouse. */
  static void unpauseClickHouse(String containerId) {
    DockerClientFactory.instance().client().unpauseContainerCmd(containerId).exec();
  }
}
