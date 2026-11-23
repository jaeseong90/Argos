/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Canned {@code CommandResult} replies of a {@link FakeAgent} ({@link FakeAgent.Builder#onCommand},
 * WO-26), shaped as 11 §8.2 says an agent answers: chunks of at most {@value #CHUNK_BYTES} bytes,
 * at most {@value #MAX_RESULT_BYTES} bytes in all ({@code truncated} beyond), {@code COOLDOWN} and
 * {@code REJECTED} without a payload.
 *
 * <pre>{@code
 * FakeAgent agent = FakeAgent.builder(channel)
 *     .token(token)
 *     .onCommand(c -> CommandReplies.threadDumpText(c, "ARGOS_SEED_cmd"))
 *     .build();
 * }</pre>
 *
 * <p>The thread dump payloads put the sentinel into thread names and frames, so a test can search
 * stored bytes, logs and responses for it (T-SEC-22).
 */
public final class CommandReplies {

  /** Bytes of one result chunk at most (11 §8.2). */
  public static final int CHUNK_BYTES = 256 * 1024;

  /** Bytes of one result at most (11 §8.2). */
  public static final int MAX_RESULT_BYTES = 1024 * 1024;

  /** {@code content_type} of a text result. */
  public static final String TEXT = "text/plain";

  /** {@code content_type} of a JSON result. */
  public static final String JSON = "application/json";

  private CommandReplies() {}

  /** A JDK-style text thread dump of three threads, the sentinel in names and frames. */
  public static List<CommandResult> threadDumpText(Command command, String sentinel) {
    return ok(command, TEXT, threadDumpTextPayload(sentinel).getBytes(StandardCharsets.UTF_8));
  }

  /** The text of {@link #threadDumpText}. */
  public static String threadDumpTextPayload(String sentinel) {
    return "\"main-"
        + sentinel
        + "\" #1 prio=5 os_prio=0 tid=0x0001 nid=0x01 runnable\n"
        + "   java.lang.Thread.State: RUNNABLE\n"
        + "\tat com.example.shop."
        + sentinel
        + ".OrderService.place(OrderService.java:42)\n"
        + "\tat com.example.shop.OrderController.post(OrderController.java:17)\n"
        + "\t- locked <0x00000000c0010000> (a java.lang.Object)\n"
        + "\n"
        + "\"worker-1\" #21 prio=5 os_prio=0 tid=0x0015 nid=0x15 waiting for monitor entry\n"
        + "   java.lang.Thread.State: BLOCKED (on object monitor)\n"
        + "\tat com.example.shop.Inventory.reserve(Inventory.java:88)\n"
        + "\t- waiting to lock <0x00000000c0010000> (a java.lang.Object)\n"
        + "\n"
        + "\"virtual-7\" #77 virtual\n"
        + "   java.lang.Thread.State: WAITING\n"
        + "\tat java.base/jdk.internal.misc.Unsafe.park(Native Method)\n"
        + "\n";
  }

  /**
   * The JSON thread dump of an agent ({@code application/json}: {@code threads[]} of {@code id,
   * name, state, virtual, locks_held[], waiting_on, frames[], tx_id?} and {@code deadlocks[]} of
   * {@code thread_ids[]}), two threads in one deadlock, the sentinel in a name and a frame.
   */
  public static List<CommandResult> threadDumpJson(Command command, String sentinel) {
    return ok(command, JSON, threadDumpJsonPayload(sentinel).getBytes(StandardCharsets.UTF_8));
  }

  /** The JSON of {@link #threadDumpJson}. */
  public static String threadDumpJsonPayload(String sentinel) {
    return "{\"threads\":["
        + "{\"id\":1,\"name\":\"http-nio-8080-exec-1 "
        + sentinel
        + "\",\"state\":\"BLOCKED\",\"virtual\":false,"
        + "\"locks_held\":[\"java.lang.Object@c0010000\"],"
        + "\"waiting_on\":\"java.lang.Object@c0020000\","
        + "\"frames\":[\"com.example.shop."
        + sentinel
        + ".OrderService.place(OrderService.java:42)\","
        + "\"com.example.shop.OrderController.post(OrderController.java:17)\"],"
        + "\"tx_id\":\"18446744073709551615\"},"
        + "{\"id\":2,\"name\":\"http-nio-8080-exec-2\",\"state\":\"BLOCKED\",\"virtual\":false,"
        + "\"locks_held\":[\"java.lang.Object@c0020000\"],"
        + "\"waiting_on\":\"java.lang.Object@c0010000\","
        + "\"frames\":[\"com.example.shop.Inventory.reserve(Inventory.java:88)\"]}"
        + "],\"deadlocks\":[{\"thread_ids\":[1,2]}]}";
  }

  /** {@code COOLDOWN}: the agent's command cooldown is running (16 §5.2). */
  public static List<CommandResult> cooldown(Command command) {
    return List.of(
        CommandResult.newBuilder()
            .setCommandId(command.getCommandId())
            .setStatus(CommandStatus.COOLDOWN)
            .setChunkIndex(0)
            .setChunkCount(1)
            .setError("Command cooldown is running")
            .build());
  }

  /**
   * {@code FAILED} with a reason (10 §12.1: an exception while running is {@code FAILED} with a
   * message, no stack; English, no collected data).
   */
  public static List<CommandResult> failed(Command command, String message) {
    return List.of(
        CommandResult.newBuilder()
            .setCommandId(command.getCommandId())
            .setStatus(CommandStatus.FAILED)
            .setChunkIndex(0)
            .setChunkCount(1)
            .setError(message)
            .build());
  }

  /**
   * An {@code OK} JSON result of {@code json} (UTF-8), chunked as an agent does, with the agent's
   * {@code truncated} flag and {@code duration_ms} (WO-37 feed answers).
   */
  public static List<CommandResult> json(
      Command command, String json, boolean truncated, int durationMs) {
    return chunks(command, JSON, json.getBytes(StandardCharsets.UTF_8), truncated, durationMs);
  }

  /** An {@code OK} text result of {@code text} (UTF-8) with {@code duration_ms}. */
  public static List<CommandResult> text(Command command, String text, int durationMs) {
    return chunks(command, TEXT, text.getBytes(StandardCharsets.UTF_8), false, durationMs);
  }

  /** {@code REJECTED}: the agent does not run this command (11 §8.2). */
  public static List<CommandResult> rejected(Command command) {
    return List.of(
        CommandResult.newBuilder()
            .setCommandId(command.getCommandId())
            .setStatus(CommandStatus.REJECTED)
            .setChunkIndex(0)
            .setChunkCount(1)
            .setError("Command type not supported")
            .build());
  }

  /**
   * A text result of {@code totalBytes} (at most {@value #MAX_RESULT_BYTES}) in chunks of {@value
   * #CHUNK_BYTES}, every chunk starting with the sentinel and padded with {@code 'x'}.
   */
  public static List<CommandResult> chunked(Command command, int totalBytes, String sentinel) {
    return chunks(command, TEXT, filler(totalBytes, sentinel), false, 0);
  }

  /**
   * A text result cut at the 1 MiB cap: {@value #MAX_RESULT_BYTES} bytes in four chunks, {@code
   * truncated = true} (11 §8.2 "초과는 truncated = true").
   */
  public static List<CommandResult> truncatedAtLimit(Command command, String sentinel) {
    return chunks(command, TEXT, filler(MAX_RESULT_BYTES, sentinel), true, 0);
  }

  /** An {@code OK} result of {@code payload}, chunked as an agent does. */
  public static List<CommandResult> ok(Command command, String contentType, byte[] payload) {
    return chunks(command, contentType, payload, false, 0);
  }

  /**
   * Chunks of {@code payload} with {@code chunk_index} and {@code chunk_count}; {@code truncated}
   * and {@code duration_ms} on every chunk.
   */
  public static List<CommandResult> chunks(
      Command command, String contentType, byte[] payload, boolean truncated, int durationMs) {
    int count = Math.max(1, (payload.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
    List<CommandResult> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int from = i * CHUNK_BYTES;
      int to = Math.min(payload.length, from + CHUNK_BYTES);
      out.add(
          CommandResult.newBuilder()
              .setCommandId(command.getCommandId())
              .setStatus(CommandStatus.OK)
              .setChunkIndex(i)
              .setChunkCount(count)
              .setPayload(ByteString.copyFrom(payload, from, to - from))
              .setContentType(contentType)
              .setTruncated(truncated)
              .setDurationMs(durationMs)
              .build());
    }
    return out;
  }

  /** {@code size} bytes: the sentinel at the start of every chunk, {@code 'x'} elsewhere. */
  private static byte[] filler(int size, String sentinel) {
    byte[] bytes = new byte[size];
    Arrays.fill(bytes, (byte) 'x');
    byte[] mark = sentinel.getBytes(StandardCharsets.UTF_8);
    for (int at = 0; at < size; at += CHUNK_BYTES) {
      System.arraycopy(mark, 0, bytes, at, Math.min(mark.length, size - at));
    }
    return bytes;
  }
}
