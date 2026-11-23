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
import java.util.Objects;

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
    StringBuilder sb = new StringBuilder(4096);
    sb.append("{\"app\":")
        .append(quote(APP))
        .append(",\"env\":")
        .append(quote(ENV))
        .append(",\"feed_start_ms\":")
        .append(feedStartMs)
        .append(",\"interval_ms\":")
        .append(intervalMs)
        .append(",\"instances\":[");
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
          .append(quote(modeText(p.ipMode())))
          .append(",\"slots\":[");
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
