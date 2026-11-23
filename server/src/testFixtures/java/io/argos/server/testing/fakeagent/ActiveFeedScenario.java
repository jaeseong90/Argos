/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.UserIdMode;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The deterministic running-transaction scenario of the WO-35 active feed ({@link ActiveFeed}):
 * what every fake instance reports in each 5-second {@code ActiveTransactionSnapshot}, as a pure
 * function of the feed start, the instance index, the snapshot number and the snapshot time. No
 * clock, no randomness, no I/O.
 *
 * <p>Two instance profiles alternate by index:
 *
 * <ul>
 *   <li>even index (the first instance): six transactions, user id mode {@code plain} and address
 *       mode {@code full}; three long ones with fixed starts 20 s, 12 s and 9 s before the feed
 *       start (they keep growing, always in the 8 s band), and three fresh ones in every snapshot
 *       at 5 s, 2 s and 0.3 s (new transactions each time, as short requests come and go). States
 *       SQL, HTTP, ASYNC, DBC, SQL and METHOD; kinds BATCH and HTTP; one virtual thread; the XSS
 *       payloads of docs/18 §4.3 (sentinel {@value #SENTINEL}) as a service name, a SQL text, a
 *       thread name and a user id; one transaction without user and address (the batch job);
 *   <li>odd index: four transactions (fewer than the first), modes {@code off} for both user id and
 *       address (the agent sends neither); one long one 10 s before the feed start, fresh ones at 4
 *       s (a BATCH connection wait), 1.5 s (an outgoing call) and 0.3 s (a method).
 * </ul>
 *
 * Every instance covers all four default elapsed bands (under 1 s, 1-3 s, 3-8 s, 8 s and more) and
 * the scenario covers the five states. Items are sorted by elapsed descending, as the agent sends
 * them (11 {@code ActiveTransactionSnapshot.items}).
 */
public final class ActiveFeedScenario {

  /** The application of the feed (agent {@code app.name}). */
  public static final String APP = "e2e-active";

  /** Its environment (agent {@code app.env}). */
  public static final String ENV = "e2e";

  /** The default instance names. */
  public static final List<String> DEFAULT_INSTANCES = List.of("e2e-active-1", "e2e-active-2");

  /** The seed sentinel of docs/18 §4.3. */
  public static final String SENTINEL = "ARGOS_SEED";

  /** XSS payload as a service name (Seeds.XSS[0] in a URL pattern). */
  public static final String XSS_SERVICE =
      "GET /api/report/<img src=x onerror=\"window.__xss=1\">" + SENTINEL;

  /** XSS payload inside a SQL text (Seeds.XSS[3]). */
  public static final String XSS_SQL =
      "SELECT r.id FROM report r WHERE r.title = '<script>window.__xss=1</script>" + SENTINEL + "'";

  /** XSS payload as a normalized thread name (Seeds.XSS[1]). */
  public static final String XSS_THREAD = "\"><svg/onload=window.__xss=1>" + SENTINEL + "-#";

  /** XSS payload as a user id (Seeds.XSS[0], below the 128-byte limit). */
  public static final String XSS_USER = "<img src=x onerror=\"window.__xss=1\">" + SENTINEL;

  /** The default elapsed bands of the server (1 s, 3 s, 8 s; 14 §4.9 {@code buckets}). */
  public static final List<Long> BAND_LIMITS_MS = List.of(1_000L, 3_000L, 8_000L);

  /** How a slot's start is chosen. */
  public enum Timing {
    /** Start = feed start + offset (negative): the same transaction in every snapshot, growing. */
    FIXED,
    /** Start = snapshot time − offset: a new transaction in every snapshot, always this elapsed. */
    FRESH
  }

  /**
   * One transaction slot of a profile.
   *
   * @param slot index in the profile (also the {@code tx_id} sequence of a fixed slot)
   * @param timing how the start is chosen
   * @param offsetMs {@link Timing#FIXED}: start relative to the feed start (negative); {@link
   *     Timing#FRESH}: elapsed at the snapshot
   * @param kind transaction kind
   * @param service service name (SERVICE)
   * @param state the in-flight state
   * @param detail the {@code state_name_id} text (SQL text, host, DB target), {@code null} for none
   * @param thread normalized thread name (THREAD)
   * @param virtualThread runs on a virtual thread
   * @param userId the user id the agent sends ({@code ""}: none)
   * @param remoteIp the client address the agent sends (literal), {@code null}: none
   */
  public record Slot(
      int slot,
      Timing timing,
      long offsetMs,
      TransactionKind kind,
      String service,
      ActiveState state,
      String detail,
      String thread,
      boolean virtualThread,
      String userId,
      String remoteIp) {

    /** The dictionary kind of {@link #detail()} (11: SQL sql_id, HTTP host, DBC DB target). */
    public DictionaryKind detailKind() {
      return switch (state) {
        case IN_SQL -> DictionaryKind.SQL_TEXT;
        case IN_HTTP -> DictionaryKind.HOST;
        case IN_DBC_WAIT -> DictionaryKind.DB_TARGET;
        default -> null;
      };
    }
  }

  /**
   * One instance profile.
   *
   * @param index instance index (0-based, also the {@code tx_id} node minus 1)
   * @param name instance name
   * @param userIdMode the Hello's user id mode
   * @param ipMode the Hello's address mode
   * @param slots the transactions
   */
  public record Profile(
      int index, String name, UserIdMode userIdMode, IpMode ipMode, List<Slot> slots) {

    /** Copies the list. */
    public Profile {
      slots = List.copyOf(slots);
    }
  }

  /**
   * A running transaction of one snapshot.
   *
   * @param slot its slot
   * @param txId the agent's {@code tx_id} (unsigned)
   * @param startMs start (agent clock)
   * @param elapsedMs elapsed at the snapshot
   */
  public record Item(Slot slot, long txId, long startMs, long elapsedMs) {}

  private final long feedStartMs;
  private final List<Profile> profiles;

  /**
   * The scenario of a feed started at {@code feedStartMs} with {@code instanceNames}.
   *
   * @param feedStartMs feed start, epoch milliseconds (after 2026-01-01T00:00:20Z)
   * @param instanceNames instance names, 1 to 64
   */
  public ActiveFeedScenario(long feedStartMs, List<String> instanceNames) {
    if (instanceNames.isEmpty() || instanceNames.size() > 64) {
      throw new IllegalArgumentException("1 to 64 instances");
    }
    if (feedStartMs - 20_000 < TxIds.EPOCH_MS) {
      throw new IllegalArgumentException("feed start before 2026-01-01T00:00:20Z");
    }
    this.feedStartMs = feedStartMs;
    List<Profile> list = new ArrayList<>();
    for (int i = 0; i < instanceNames.size(); i++) {
      list.add(profile(i, Objects.requireNonNull(instanceNames.get(i), "instance name")));
    }
    this.profiles = List.copyOf(list);
  }

  /** The feed start, epoch milliseconds. */
  public long feedStartMs() {
    return feedStartMs;
  }

  /** The instance profiles, by index. */
  public List<Profile> profiles() {
    return profiles;
  }

  /** The profile of instance {@code index}. */
  public Profile profile(int index) {
    return profiles.get(index);
  }

  private static Profile profile(int index, String name) {
    String exec = "http-nio-8080-exec-#";
    String db = "jdbc:postgresql://db.internal:5432/shop";
    if (index % 2 == 0) {
      return new Profile(
          index,
          name,
          UserIdMode.USER_ID_PLAIN,
          IpMode.IP_FULL,
          List.of(
              new Slot(
                  0,
                  Timing.FIXED,
                  -20_000,
                  TransactionKind.BATCH,
                  "nightly-settlement",
                  ActiveState.IN_SQL,
                  "UPDATE settlement s SET s.status = ? WHERE s.batch_id = ?",
                  "batch-worker-#",
                  false,
                  "",
                  null),
              new Slot(
                  1,
                  Timing.FIXED,
                  -12_000,
                  TransactionKind.HTTP,
                  XSS_SERVICE,
                  ActiveState.IN_HTTP,
                  "http://inventory.internal:8080",
                  exec,
                  false,
                  XSS_USER,
                  "10.1.2.3"),
              new Slot(
                  2,
                  Timing.FIXED,
                  -9_000,
                  TransactionKind.HTTP,
                  "GET /api/exports/{id}",
                  ActiveState.IN_ASYNC,
                  null,
                  XSS_THREAD,
                  false,
                  "bob",
                  "2001:db8::7"),
              new Slot(
                  3,
                  Timing.FRESH,
                  5_000,
                  TransactionKind.HTTP,
                  "POST /api/orders",
                  ActiveState.IN_DBC_WAIT,
                  db,
                  exec,
                  false,
                  "alice",
                  "10.1.2.4"),
              new Slot(
                  4,
                  Timing.FRESH,
                  2_000,
                  TransactionKind.HTTP,
                  "GET /api/orders/{id}",
                  ActiveState.IN_SQL,
                  XSS_SQL,
                  exec,
                  false,
                  "alice",
                  "10.1.2.5"),
              new Slot(
                  5,
                  Timing.FRESH,
                  300,
                  TransactionKind.HTTP,
                  "GET /api/products",
                  ActiveState.IN_METHOD,
                  null,
                  "virtual-#",
                  true,
                  "carol",
                  "10.1.2.6")));
    }
    return new Profile(
        index,
        name,
        UserIdMode.USER_ID_OFF,
        IpMode.IP_OFF,
        List.of(
            new Slot(
                0,
                Timing.FIXED,
                -10_000,
                TransactionKind.HTTP,
                "GET /api/orders/{id}",
                ActiveState.IN_SQL,
                "SELECT o.id, o.total FROM orders o WHERE o.id = ?",
                exec,
                false,
                "",
                null),
            new Slot(
                1,
                Timing.FRESH,
                4_000,
                TransactionKind.BATCH,
                "inventory-sync",
                ActiveState.IN_DBC_WAIT,
                db,
                "batch-worker-#",
                false,
                "",
                null),
            new Slot(
                2,
                Timing.FRESH,
                1_500,
                TransactionKind.HTTP,
                "GET /api/products",
                ActiveState.IN_HTTP,
                "http://pricing.internal:9090",
                exec,
                false,
                "",
                null),
            new Slot(
                3,
                Timing.FRESH,
                300,
                TransactionKind.HTTP,
                "GET /api/orders/{id}",
                ActiveState.IN_METHOD,
                null,
                exec,
                false,
                "",
                null)));
  }

  /**
   * The running transactions of instance {@code index} in snapshot {@code number} (0-based) taken
   * at {@code snapshotTsMs}, elapsed descending.
   */
  public List<Item> items(int index, long number, long snapshotTsMs) {
    if (snapshotTsMs < feedStartMs) {
      throw new IllegalArgumentException("snapshot before the feed start");
    }
    Profile p = profiles.get(index);
    int node = (index + 1) & 0xFFF;
    List<Item> out = new ArrayList<>(p.slots().size());
    for (Slot s : p.slots()) {
      long start;
      int seq;
      if (s.timing() == Timing.FIXED) {
        start = feedStartMs + s.offsetMs();
        seq = s.slot();
      } else {
        start = snapshotTsMs - s.offsetMs();
        seq = (int) ((16 + number * 8 + s.slot()) & 0x7FF);
      }
      out.add(new Item(s, TxIds.of(start, node, seq), start, snapshotTsMs - start));
    }
    out.sort(
        Comparator.comparingLong(Item::elapsedMs)
            .reversed()
            .thenComparingInt(i -> i.slot().slot()));
    return List.copyOf(out);
  }

  /** The elapsed band of {@code elapsedMs} with the default limits: 0 (under 1 s) to 3 (8 s+). */
  public static int band(long elapsedMs) {
    int band = 0;
    for (long limit : BAND_LIMITS_MS) {
      if (elapsedMs >= limit) {
        band++;
      }
    }
    return band;
  }

  /** Per band (0..3) the item count of {@code items}. */
  public static long[] bands(List<Item> items) {
    long[] counts = new long[4];
    for (Item i : items) {
      counts[band(i.elapsedMs())]++;
    }
    return counts;
  }

  /**
   * The protobuf item of {@code item}; every name gets its dictionary id from {@code records}
   * (which remembers the entry for the pre-send of 11 §7).
   */
  public static ActiveTransaction toProto(Item item, int threadIdBase, Records records) {
    Slot s = item.slot();
    ActiveTransaction.Builder b =
        ActiveTransaction.newBuilder()
            .setTxId(item.txId())
            .setServiceId(records.id(DictionaryKind.SERVICE, s.service()))
            .setKind(s.kind())
            .setStartTsMs(item.startMs())
            .setElapsedMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, item.elapsedMs())))
            .setState(s.state())
            .setThreadNameId(records.id(DictionaryKind.THREAD, s.thread()))
            .setThreadId(threadIdBase + s.slot())
            .setVirtualThread(s.virtualThread())
            .setUserId(s.userId());
    if (s.detail() != null && s.detailKind() != null) {
      b.setStateNameId(records.id(s.detailKind(), s.detail()));
    }
    if (s.remoteIp() != null) {
      b.setRemoteIp(ByteString.copyFrom(addressBytes(s.remoteIp())));
    }
    return b.build();
  }

  /** Registers every name of {@code profile} in {@code records} (the dictionary pre-send). */
  public static void registerNames(Profile profile, Records records) {
    for (Slot s : profile.slots()) {
      records.id(DictionaryKind.SERVICE, s.service());
      records.id(DictionaryKind.THREAD, s.thread());
      if (s.detail() != null && s.detailKind() != null) {
        records.id(s.detailKind(), s.detail());
      }
    }
  }

  // --- Remote command answers (WO-37, ActiveFeed --commands) ------------------------------------

  /** Pool threads idle on the same stack in every dump (the viewer collapses them, 05 §2.7-5). */
  public static final int IDLE_POOL_THREADS = 10;

  /** Frames of the deepest thread of every dump: exactly the agent's depth cap (16 §1.4). */
  public static final int DEEP_FRAMES = 64;

  /** The two threads of the deadlock of every dump (each holds the lock the other waits on). */
  public static final List<String> DEADLOCK_THREADS =
      List.of("order-settle-worker", "inventory-sync-worker");

  /** XSS payload as a thread name of the dump (Seeds.XSS[0]). */
  public static final String XSS_DUMP_THREAD =
      "<img src=x onerror=\"window.__xss=1\">" + SENTINEL + "-worker";

  /** XSS payload inside a frame of the dump (Seeds.XSS[3]). */
  public static final String XSS_FRAME =
      "com.example.shop.<script>window.__xss=1</script>"
          + SENTINEL
          + ".Report.render(Report.java:13)";

  /**
   * The raw value of the secret-like variables of the environment dump: it never leaves the feed
   * (the agent masks it, SEC-20); tests search results, pages and logs for it.
   */
  public static final String ENV_SECRET_RAW = SENTINEL + "-env-secret-value";

  /** What the agent sends instead of {@link #ENV_SECRET_RAW} (10 §12.1 {@code EnvMasker}). */
  public static final String ENV_SECRET_MASKED = "****(len=" + ENV_SECRET_RAW.length() + ")";

  /** XSS payload as a non-secret environment value (shown as text, Seeds.XSS[0]). */
  public static final String XSS_ENV_VALUE = "<img src=x onerror=\"window.__xss=1\">" + SENTINEL;

  /** The {@code thread_id} base of instance {@code index}: a transaction thread is base + slot. */
  public static int threadIdBase(int index) {
    return 1_000 * (index + 1);
  }

  /**
   * Whether the agent of instance {@code index} reports its thread dump as cut at 1 MiB ({@code
   * truncated = true}): the second instance (index 1) does, so the viewer's truncation notice has a
   * real result.
   */
  public static boolean dumpTruncated(int index) {
    return index == 1;
  }

  /**
   * One thread of a fake dump, in the agent's JSON form (14 §4.10: {@code id, name, state, virtual,
   * locks_held[], waiting_on, frames[], tx_id?}).
   *
   * @param state null: the agent sent no state
   * @param waitingOn null: waits on nothing
   * @param txId the running transaction (unsigned decimal), null for none
   */
  public record DumpThread(
      long id,
      String name,
      String state,
      boolean virtual,
      List<String> locksHeld,
      String waitingOn,
      List<String> frames,
      String txId) {

    /** Copies the lists. */
    public DumpThread {
      locksHeld = List.copyOf(locksHeld);
      frames = List.copyOf(frames);
    }
  }

  /**
   * The thread dump of instance {@code index} while {@code items} run (the newest snapshot): one
   * thread per running transaction ({@code tx_id}, thread id {@link #threadIdBase} + slot, the
   * concrete name of the slot's normalized thread name, a state and stack that fit the in-flight
   * state, virtual when the slot is), then {@code main} (RUNNABLE), {@value #IDLE_POOL_THREADS}
   * idle pool threads with one identical stack (WAITING), a scheduler (TIMED_WAITING), the
   * deadlocked pair of {@link #DEADLOCK_THREADS} (BLOCKED), two virtual threads (one unnamed), one
   * thread of exactly {@value #DEEP_FRAMES} frames, the XSS payloads of a name and a frame, and
   * {@code Signal Dispatcher} without a state.
   */
  public List<DumpThread> dumpThreads(int index, List<Item> items) {
    int base = threadIdBase(index);
    List<DumpThread> out = new ArrayList<>();
    for (Item item : items) {
      out.add(transactionThread(item, base));
    }
    out.add(
        new DumpThread(
            1,
            "main",
            "RUNNABLE",
            false,
            List.of(),
            null,
            List.of(
                "sun.nio.ch.Net.accept(Native Method)",
                "sun.nio.ch.NioSocketImpl.accept(NioSocketImpl.java:748)",
                "org.apache.catalina.core.StandardServer.await(StandardServer.java:567)",
                "org.springframework.boot.SpringApplication.run(SpringApplication.java:335)",
                "com.example.shop.ShopApplication.main(ShopApplication.java:12)"),
            null));
    for (int i = 0; i < IDLE_POOL_THREADS; i++) {
      out.add(
          new DumpThread(
              base + 100 + i,
              "http-nio-8080-exec-" + (11 + i),
              "WAITING",
              false,
              List.of(),
              "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject@3c0f8a21",
              List.of(
                  "jdk.internal.misc.Unsafe.park(Native Method)",
                  "java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)",
                  "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await("
                      + "AbstractQueuedSynchronizer.java:1761)",
                  "java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)",
                  "org.apache.tomcat.util.threads.TaskQueue.take(TaskQueue.java:141)",
                  "org.apache.tomcat.util.threads.ThreadPoolExecutor.getTask("
                      + "ThreadPoolExecutor.java:1114)",
                  "org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker("
                      + "ThreadPoolExecutor.java:1176)",
                  "org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run("
                      + "TaskThread.java:63)",
                  "java.lang.Thread.run(Thread.java:1583)"),
              null));
    }
    out.add(
        new DumpThread(
            base + 150,
            "scheduling-1",
            "TIMED_WAITING",
            false,
            List.of(),
            null,
            List.of(
                "java.lang.Thread.sleep0(Native Method)",
                "java.lang.Thread.sleep(Thread.java:509)",
                "com.example.shop.jobs.CacheRefresher.run(CacheRefresher.java:31)",
                "java.lang.Thread.run(Thread.java:1583)"),
            null));
    String lockA = "com.example.shop.Settlement@6d1e7a10";
    String lockB = "com.example.shop.Inventory@1f4c2b30";
    out.add(
        new DumpThread(
            base + 201,
            DEADLOCK_THREADS.get(0),
            "BLOCKED",
            false,
            List.of(lockA),
            lockB,
            List.of(
                "com.example.shop.Inventory.release(Inventory.java:120)",
                "com.example.shop.Settlement.close(Settlement.java:77)",
                "java.lang.Thread.run(Thread.java:1583)"),
            null));
    out.add(
        new DumpThread(
            base + 202,
            DEADLOCK_THREADS.get(1),
            "BLOCKED",
            false,
            List.of(lockB),
            lockA,
            List.of(
                "com.example.shop.Settlement.adjust(Settlement.java:54)",
                "com.example.shop.Inventory.sync(Inventory.java:98)",
                "java.lang.Thread.run(Thread.java:1583)"),
            null));
    out.add(
        new DumpThread(
            base + 300,
            "",
            "WAITING",
            true,
            List.of(),
            null,
            List.of(
                "java.lang.VirtualThread.park(VirtualThread.java:582)",
                "java.lang.System$2.parkVirtualThread(System.java:2643)",
                "java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)",
                "com.example.shop.events.OrderEvents.next(OrderEvents.java:40)",
                "java.lang.VirtualThread.run(VirtualThread.java:309)"),
            null));
    out.add(
        new DumpThread(
            base + 301,
            "order-events-vt",
            "RUNNABLE",
            true,
            List.of(),
            null,
            List.of(
                "com.example.shop.events.OrderEvents.publish(OrderEvents.java:64)",
                "java.lang.VirtualThread.run(VirtualThread.java:309)"),
            null));
    List<String> deep = new ArrayList<>(DEEP_FRAMES);
    for (int i = 0; i < DEEP_FRAMES - 2; i++) {
      deep.add("com.example.shop.tree.CategoryNode.visit(CategoryNode.java:" + (40 + i % 7) + ")");
    }
    deep.add("com.example.shop.tree.CategoryTree.walk(CategoryTree.java:22)");
    deep.add("java.lang.Thread.run(Thread.java:1583)");
    out.add(
        new DumpThread(
            base + 400, "category-walker", "RUNNABLE", false, List.of(), null, deep, null));
    out.add(
        new DumpThread(
            base + 500,
            XSS_DUMP_THREAD,
            "TIMED_WAITING",
            false,
            List.of(),
            null,
            List.of(
                XSS_FRAME,
                "java.lang.Thread.sleep(Thread.java:509)",
                "java.lang.Thread.run(Thread.java:1583)"),
            null));
    out.add(new DumpThread(4, "Signal Dispatcher", null, false, List.of(), null, List.of(), null));
    return List.copyOf(out);
  }

  /** The deadlock cycles of {@link #dumpThreads}: the ids of the {@link #DEADLOCK_THREADS}. */
  public static List<List<Long>> dumpDeadlocks(int index) {
    int base = threadIdBase(index);
    return List.of(List.of(base + 201L, base + 202L));
  }

  /** The concrete thread name of a slot ({@code #} of the normalized name → slot + 1). */
  public static String concreteThreadName(Slot slot) {
    return slot.thread().replace("#", Integer.toString(slot.slot() + 1));
  }

  private static DumpThread transactionThread(Item item, int base) {
    Slot s = item.slot();
    String app = appFrame(s);
    String tail =
        s.virtualThread()
            ? "java.lang.VirtualThread.run(VirtualThread.java:309)"
            : s.kind() == TransactionKind.BATCH
                ? "java.lang.Thread.run(Thread.java:1583)"
                : "org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:63)";
    String state;
    String waitingOn = null;
    List<String> frames = new ArrayList<>();
    switch (s.state()) {
      case IN_SQL -> {
        state = "RUNNABLE";
        frames.add("sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:327)");
        frames.add("org.postgresql.core.PGStream.receiveChar(PGStream.java:467)");
        frames.add("org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:356)");
        frames.add("org.postgresql.jdbc.PgPreparedStatement.execute(PgPreparedStatement.java:182)");
        frames.add(
            "com.zaxxer.hikari.pool.HikariProxyPreparedStatement.execute("
                + "HikariProxyPreparedStatement.java)");
      }
      case IN_HTTP -> {
        state = "RUNNABLE";
        frames.add("sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:327)");
        frames.add(
            "org.apache.hc.core5.http.impl.io.SessionInputBufferImpl.fillBuffer("
                + "SessionInputBufferImpl.java:149)");
        frames.add(
            "org.apache.hc.client5.http.impl.classic.InternalHttpClient.doExecute("
                + "InternalHttpClient.java:174)");
      }
      case IN_ASYNC -> {
        state = "WAITING";
        waitingOn = "java.util.concurrent.CompletableFuture$Signaller@5e1f0a01";
        frames.add("jdk.internal.misc.Unsafe.park(Native Method)");
        frames.add("java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)");
        frames.add(
            "java.util.concurrent.CompletableFuture$Signaller.block(CompletableFuture.java:1898)");
        frames.add("java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2096)");
      }
      case IN_DBC_WAIT -> {
        state = "TIMED_WAITING";
        waitingOn = "java.util.concurrent.SynchronousQueue$TransferStack@2b9d4c11";
        frames.add("jdk.internal.misc.Unsafe.park(Native Method)");
        frames.add("java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:269)");
        frames.add("java.util.concurrent.SynchronousQueue.poll(SynchronousQueue.java:903)");
        frames.add("com.zaxxer.hikari.util.ConcurrentBag.borrow(ConcurrentBag.java:151)");
        frames.add("com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)");
      }
      default -> state = "RUNNABLE";
    }
    frames.add(app);
    frames.add(tail);
    return new DumpThread(
        base + s.slot(),
        concreteThreadName(s),
        state,
        s.virtualThread(),
        List.of(),
        waitingOn,
        frames,
        Long.toUnsignedString(item.txId()));
  }

  /** The application frame of a slot (its service, without collected text in the method name). */
  private static String appFrame(Slot s) {
    return switch (s.slot()) {
      case 0 ->
          s.kind() == TransactionKind.BATCH
              ? "com.example.shop.batch.SettlementJob.run(SettlementJob.java:88)"
              : "com.example.shop.OrderService.find(OrderService.java:61)";
      case 1 -> "com.example.shop.ReportService.fetch(ReportService.java:45)";
      case 2 -> "com.example.shop.ExportController.get(ExportController.java:39)";
      case 3 -> "com.example.shop.OrderService.place(OrderService.java:42)";
      case 4 -> "com.example.shop.OrderService.find(OrderService.java:61)";
      default -> "com.example.shop.ProductCatalog.list(ProductCatalog.java:57)";
    };
  }

  /**
   * A thread dump in the agent's JSON form ({@code threads[]} and {@code deadlocks[]} of {@code
   * thread_ids[]}).
   */
  public static String dumpJson(List<DumpThread> threads, List<List<Long>> deadlocks) {
    StringBuilder sb = new StringBuilder(16_384).append("{\"threads\":[");
    for (int i = 0; i < threads.size(); i++) {
      DumpThread t = threads.get(i);
      sb.append(i == 0 ? "" : ",")
          .append("{\"id\":")
          .append(t.id())
          .append(",\"name\":")
          .append(quote(t.name()));
      if (t.state() != null) {
        sb.append(",\"state\":").append(quote(t.state()));
      }
      sb.append(",\"virtual\":").append(t.virtual()).append(",\"locks_held\":");
      strings(sb, t.locksHeld());
      sb.append(",\"waiting_on\":").append(t.waitingOn() == null ? "null" : quote(t.waitingOn()));
      sb.append(",\"frames\":");
      strings(sb, t.frames());
      if (t.txId() != null) {
        sb.append(",\"tx_id\":").append(quote(t.txId()));
      }
      sb.append('}');
    }
    sb.append("],\"deadlocks\":[");
    for (int i = 0; i < deadlocks.size(); i++) {
      sb.append(i == 0 ? "" : ",").append("{\"thread_ids\":[");
      List<Long> ids = deadlocks.get(i);
      for (int j = 0; j < ids.size(); j++) {
        sb.append(j == 0 ? "" : ",").append(ids.get(j));
      }
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }

  private static void strings(StringBuilder sb, List<String> values) {
    sb.append('[');
    for (int i = 0; i < values.size(); i++) {
      sb.append(i == 0 ? "" : ",").append(quote(values.get(i)));
    }
    sb.append(']');
  }

  /**
   * One entry of the self-diagnosis {@code effective_config}.
   *
   * @param source {@code file}, {@code env}, {@code server} or {@code default}
   */
  public record ConfigEntry(String key, String value, String source) {}

  /** The effective configuration the self diagnosis of every instance reports. */
  public static final List<ConfigEntry> EFFECTIVE_CONFIG =
      List.of(
          new ConfigEntry("server.address", "127.0.0.1:9100", "file"),
          new ConfigEntry("server.tls", "false", "env"),
          new ConfigEntry("server.token-file", "/run/secrets/argos-agent.token", "env"),
          new ConfigEntry("server.token", ENV_SECRET_MASKED, "env"),
          new ConfigEntry("sampling.normal-rate", "0.5", "remote"),
          new ConfigEntry("profile.sql.bind-capture", "false", "default"),
          new ConfigEntry("capture.user-id.mode", "plain", "file"),
          new ConfigEntry("capture.ip.mode", "full", "file"),
          new ConfigEntry("commands.thread-dump.cooldown-seconds", "5", "default"));

  /** The keys the server may change remotely ({@code remote-config.allow}). */
  public static final List<String> REMOTE_CONFIG_ALLOW =
      List.of("sampling.normal-rate", "profile.sql.bind-capture", "ignore.services");

  /**
   * One module of the self diagnosis.
   *
   * @param state {@code ModuleHealth} state name
   * @param reason the agent's reason for a module that is not enabled, null when enabled
   */
  public record ModuleEntry(int id, String name, String state, long errors, String reason) {}

  /** The instrumentation modules the self diagnosis reports (one disabled by configuration). */
  public static final List<ModuleEntry> MODULES =
      List.of(
          new ModuleEntry(0, "servlet", "ENABLED", 0, null),
          new ModuleEntry(1, "spring-mvc", "ENABLED", 0, null),
          new ModuleEntry(2, "jdbc", "ENABLED", 0, null),
          new ModuleEntry(3, "httpclient", "ENABLED", 0, null),
          new ModuleEntry(
              4,
              "executor",
              "DISABLED_BY_CONFIG",
              0,
              "instrumentation.disabled (agent configuration at start)"));

  /**
   * The self diagnosis of instance {@code index} in the agent's own layout (agent-core {@code
   * SelfDiagCommand}, WO-17; ADR-0010 C-234 (1)): the {@code SelfDiagnostics} format-1 members
   * ({@code format}, the gauges, {@code counters}, {@code
   * modules[{id,name,state,errors,reason?}]}), then {@code agent}, {@code jvm}, {@code uptime_ms},
   * {@code config_hash}, {@code effective_config} as a map key to {@code {value, source}} (source
   * {@code default}, {@code file}, {@code sysprop}, {@code env} or {@code remote}), {@code
   * remote_config_allow}, {@code capture}, {@code gauges}, {@code active_registry}, {@code
   * threads}, {@code clock_offset_ms}, {@code virtual_threads}, {@code cgroup} and {@code
   * truncated}. The token is masked; no collected data.
   */
  public String selfDiagnosisJson(int index, long uptimeMs) {
    Profile p = profiles.get(index);
    StringBuilder sb = new StringBuilder(2_048);
    sb.append("{\"format\":1")
        .append(",\"threadlocal_contexts_live\":")
        .append(p.slots().size())
        .append(",\"active_registry_size\":")
        .append(p.slots().size())
        .append(",\"weakmap_size\":12,\"weakmap_entries\":12")
        .append(",\"agent_threads_cpu_seconds_total\":1.250")
        .append(",\"direct_memory_bytes\":1048576")
        .append(",\"premain_ms\":412.000")
        .append(",\"counters\":{")
        .append("\"argos_agent_dropped_total{queue=\\\"transaction\\\"}\":0,")
        .append("\"argos_agent_dictionary_overflow_total\":0,")
        .append("\"argos_agent_registry_overflow_total{registry=\\\"pool\\\"}\":0},")
        .append("\"modules\":[");
    for (int i = 0; i < MODULES.size(); i++) {
      ModuleEntry m = MODULES.get(i);
      sb.append(i == 0 ? "" : ",")
          .append("{\"id\":")
          .append(m.id())
          .append(",\"name\":")
          .append(quote(m.name()))
          .append(",\"state\":")
          .append(quote(m.state()))
          .append(",\"errors\":")
          .append(m.errors());
      if (m.reason() != null) {
        sb.append(",\"reason\":").append(quote(m.reason()));
      }
      sb.append('}');
    }
    sb.append("],\"agent\":{\"version\":\"0.0.0-SNAPSHOT\",\"commit\":\"feed\"")
        .append(",\"protocol\":\"1\",\"release_build\":false}")
        .append(",\"jvm\":{\"java_version\":\"21.0.5\",\"vm_name\":\"OpenJDK 64-Bit Server VM\"")
        .append(",\"feature_version\":21}")
        .append(",\"uptime_ms\":")
        .append(Math.max(0, uptimeMs))
        .append(",\"config_hash\":\"feed")
        .append(index)
        .append("\",\"effective_config\":{");
    for (int i = 0; i < EFFECTIVE_CONFIG.size(); i++) {
      ConfigEntry c = EFFECTIVE_CONFIG.get(i);
      sb.append(i == 0 ? "" : ",")
          .append(quote(c.key()))
          .append(":{\"value\":")
          .append(quote(c.value()))
          .append(",\"source\":")
          .append(quote(c.source()))
          .append('}');
    }
    sb.append("},\"remote_config_allow\":");
    strings(sb, REMOTE_CONFIG_ALLOW);
    sb.append(",\"capture\":{\"user_id_mode\":")
        .append(quote(modeText(p.userIdMode())))
        .append(",\"remote_ip\":")
        .append(quote(modeText(p.ipMode())))
        .append("},\"gauges\":{\"queue_transaction\":0,\"heap_bytes_estimated\":8388608}")
        .append(",\"active_registry\":{\"size\":")
        .append(p.slots().size())
        .append(",\"capacity\":4096,\"rejected\":0,\"virtual\":0}")
        .append(",\"threads\":[{\"name\":\"argos-io\",\"state\":\"RUNNABLE\",\"daemon\":true}]")
        .append(",\"clock_offset_ms\":0,\"virtual_threads\":{\"supported\":true}")
        .append(",\"cgroup\":\"none\",\"truncated\":false}");
    return sb.toString();
  }

  /**
   * The environment dump of every instance in the agent's own {@code text/plain} form (agent-core
   * {@code EnvDumpCommand}, WO-17; ADR-0010 C-234 (1)): sections {@code # env (N)}, {@code #
   * properties (N)} and {@code # args (N)} of {@code key=value} lines (keys sorted) and argument
   * lines, separated by blank lines. Every secret-like key carries {@link #ENV_SECRET_MASKED},
   * never {@link #ENV_SECRET_RAW}; one plain value is an XSS payload; a long Unix path mixing
   * {@code /} and {@code -} stays readable (C-235 (3)(c)).
   */
  public static String envDumpText() {
    String m = ENV_SECRET_MASKED;
    Map<String, String> env = new TreeMap<>();
    env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
    env.put("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64");
    env.put("TZ", "UTC");
    env.put("SHOP_BANNER", XSS_ENV_VALUE);
    env.put("DB_PASSWORD", m);
    env.put("ARGOS_TOKEN", m);
    env.put("AWS_SECRET_ACCESS_KEY", m);
    Map<String, String> properties = new TreeMap<>();
    properties.put("java.version", "21.0.5");
    properties.put("java.vendor", "Eclipse Adoptium");
    properties.put("user.timezone", "UTC");
    properties.put("file.encoding", "UTF-8");
    properties.put("spring.datasource.password", m);
    properties.put("javax.net.ssl.trustStorePassword", m);
    List<String> args =
        List.of(
            "-Xms256m",
            "-Xmx512m",
            "-XX:+UseG1GC",
            "-javaagent:/opt/argos/argos-agent.jar",
            "-Dspring.profiles.active=e2e",
            "-Dspring.datasource.password=" + m);
    StringBuilder sb = new StringBuilder(1_024);
    sb.append("# env (").append(env.size()).append(")\n");
    env.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
    sb.append("\n# properties (").append(properties.size()).append(")\n");
    properties.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
    sb.append("\n# args (").append(args.size()).append(")\n");
    args.forEach(a -> sb.append(a).append('\n'));
    return sb.toString();
  }

  /** The server's text of a state (14 §4.9: SQL, HTTP, DBC, METHOD, ASYNC). */
  public static String stateText(ActiveState state) {
    return switch (state) {
      case IN_SQL -> "SQL";
      case IN_HTTP -> "HTTP";
      case IN_DBC_WAIT -> "DBC";
      case IN_ASYNC -> "ASYNC";
      default -> "METHOD";
    };
  }

  /** The server's stored name of a user id mode ({@code off}, {@code plain}, {@code hashed}). */
  public static String modeText(UserIdMode mode) {
    return switch (mode) {
      case USER_ID_PLAIN -> "plain";
      case USER_ID_HASHED -> "hashed";
      default -> "off";
    };
  }

  /** The server's stored name of an address mode ({@code off}, {@code masked}, {@code full}). */
  public static String modeText(IpMode mode) {
    return switch (mode) {
      case IP_FULL -> "full";
      case IP_MASKED -> "masked";
      default -> "off";
    };
  }

  /**
   * The address as the server shows it ({@code InetAddress.getHostAddress()} of the bytes), {@code
   * null} for none or when the instance's mode is {@code off} (C-143 (1)).
   */
  public static String shownAddress(Slot slot, IpMode mode) {
    if (slot.remoteIp() == null || mode == IpMode.IP_OFF) {
      return null;
    }
    try {
      return InetAddress.getByAddress(addressBytes(slot.remoteIp())).getHostAddress();
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The 4 or 16 bytes of an address literal (no name lookup). */
  static byte[] addressBytes(String literal) {
    if (!literal.matches("[0-9a-fA-F:.]+")) {
      throw new IllegalArgumentException("not an address literal: " + literal);
    }
    try {
      // A literal never triggers a lookup.
      return InetAddress.getByName(literal).getAddress();
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("not an address literal: " + literal, e);
    }
  }

  /**
   * The scenario as JSON (the ready file of {@link ActiveFeed}, read by ui/e2e/stack/seed/
   * activeFeed.ts): feed start, interval, and per instance its name, id, modes and slots with what
   * the server shows for them ({@code state} text, {@code state_detail}, {@code user_id} null when
   * none, {@code remote_ip} as shown).
   */
  public String toJson(long intervalMs, List<Integer> instanceIds) {
    return toJson(intervalMs, instanceIds, APP, ENV, false);
  }

  /**
   * {@link #toJson(long, List)} for the feed's {@code app} and {@code env}; with {@code commands}
   * (ActiveFeed {@code --commands}, WO-37) also {@code commands} = what the command answers contain
   * (the environment secret's raw sentinel and masked form, the deadlock thread names, the idle
   * pool threads, the deepest stack) and per instance {@code dump_truncated}; {@code commands} is
   * null without.
   */
  public String toJson(
      long intervalMs, List<Integer> instanceIds, String app, String env, boolean commands) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append("{\"app\":")
        .append(quote(app))
        .append(",\"env\":")
        .append(quote(env))
        .append(",\"feed_start_ms\":")
        .append(feedStartMs)
        .append(",\"interval_ms\":")
        .append(intervalMs)
        .append(",\"commands\":");
    if (commands) {
      sb.append("{\"enabled\":true,\"env_secret_sentinel\":")
          .append(quote(ENV_SECRET_RAW))
          .append(",\"env_secret_masked\":")
          .append(quote(ENV_SECRET_MASKED))
          .append(",\"deadlock_threads\":");
      strings(sb, DEADLOCK_THREADS);
      sb.append(",\"idle_pool_threads\":")
          .append(IDLE_POOL_THREADS)
          .append(",\"deep_frames\":")
          .append(DEEP_FRAMES)
          .append(",\"xss_thread\":")
          .append(quote(XSS_DUMP_THREAD))
          .append('}');
    } else {
      sb.append("null");
    }
    sb.append(",\"instances\":[");
    for (int i = 0; i < profiles.size(); i++) {
      Profile p = profiles.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"name\":")
          .append(quote(p.name()))
          .append(",\"instance_id\":")
          .append(i < instanceIds.size() ? instanceIds.get(i) : 0)
          .append(",\"user_id_mode\":")
          .append(quote(modeText(p.userIdMode())))
          .append(",\"ip_mode\":")
          .append(quote(modeText(p.ipMode())));
      if (commands) {
        sb.append(",\"dump_truncated\":").append(dumpTruncated(i));
      }
      sb.append(",\"slots\":[");
      for (int j = 0; j < p.slots().size(); j++) {
        Slot s = p.slots().get(j);
        if (j > 0) {
          sb.append(',');
        }
        sb.append("{\"slot\":")
            .append(s.slot())
            .append(",\"timing\":")
            .append(quote(s.timing().name().toLowerCase(Locale.ROOT)))
            .append(",\"start_ms\":")
            .append(s.timing() == Timing.FIXED ? Long.toString(feedStartMs + s.offsetMs()) : "null")
            .append(",\"elapsed_ms\":")
            .append(s.timing() == Timing.FRESH ? Long.toString(s.offsetMs()) : "null")
            .append(",\"kind\":")
            .append(quote(s.kind().name()))
            .append(",\"service\":")
            .append(quote(s.service()))
            .append(",\"state\":")
            .append(quote(stateText(s.state())))
            .append(",\"state_detail\":")
            .append(s.detailKind() == null ? "null" : quote(s.detail()))
            .append(",\"thread\":")
            .append(quote(s.thread()))
            .append(",\"virtual_thread\":")
            .append(s.virtualThread())
            .append(",\"user_id\":")
            .append(s.userId().isEmpty() ? "null" : quote(s.userId()))
            .append(",\"remote_ip\":");
        String shown = shownAddress(s, p.ipMode());
        sb.append(shown == null ? "null" : quote(shown)).append('}');
      }
      sb.append("]}");
    }
    sb.append("]}");
    return sb.toString();
  }

  /** A JSON string literal. */
  static String quote(String value) {
    StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
