/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.transport.RemoteDispatcher;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The agent's {@link RemoteDispatcher} (WO-17, 10 §12.1, 11 §8, SEC-24, SEC-91): runs commands and
 * configuration changes on the {@code argos-command} agent thread, one at a time.
 *
 * <ul>
 *   <li>The thread starts with the first message that needs it, never on the premain thread
 *       (T-NFR-30a); calls from {@code argos-io} only enqueue and return.
 *   <li>Commands: the type list is closed ({@code CommandType}, C-103): an unknown or unset type,
 *       or a type without a handler, is answered {@code REJECTED} at once. At most {@value
 *       #MAX_QUEUED_COMMANDS} commands wait; one more is {@code REJECTED} ({@code busy}). The TTL
 *       ({@code ttl_s}, 0 = {@value #DEFAULT_TTL_SECONDS} s) is measured from the receipt on this
 *       agent (the clocks of the server and the agent may differ): a command that has not started
 *       within it is answered {@code EXPIRED}. A type in its cooldown ({@link Cooldown}) is
 *       answered {@code COOLDOWN}. A handler failure is {@code FAILED} with a message of at most
 *       {@value #MAX_ERROR_BYTES} bytes (exception class and message, never a stack trace). The
 *       payload is cut at {@value #MAX_PAYLOAD_BYTES} bytes ({@code truncated}) and sent in chunks
 *       of at most {@value #CHUNK_BYTES} bytes. One INFO line per command (type, status,
 *       milliseconds; never the content).
 *   <li>{@code ConfigPush}: applied by {@link ConfigPushApplier}; at most {@value
 *       #MAX_QUEUED_CONFIG} wait, one more is answered with every key {@code LIMIT_EXCEEDED}
 *       ({@code busy}). Without an applier every key is {@code NOT_ALLOWED}.
 *   <li>{@code ServerHello.config}: only the latest one waits; the kill switch part applies at once
 *       ({@link ConfigPushApplier#helloKillSwitch}).
 * </ul>
 */
public final class CommandDispatcher implements RemoteDispatcher {
  static final int MAX_QUEUED_COMMANDS = 8;
  static final int MAX_QUEUED_CONFIG = 8;
  static final long DEFAULT_TTL_SECONDS = 60;
  static final int CHUNK_BYTES = 256 * 1024;
  static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  static final int MAX_ERROR_BYTES = 1024;

  /** The name of the agent thread (10 §14 thread budget). */
  public static final String THREAD_NAME = "argos-command";

  static final String UNKNOWN_TYPE = "rejected: unknown command type";
  static final String NOT_AVAILABLE = "rejected: command not available in this agent";
  static final String BUSY = "rejected: busy, too many commands waiting";
  static final String EXPIRED = "expired: not started within ttl_s of its receipt";
  static final String CONFIG_BUSY = "limit_exceeded: busy, too many configuration changes waiting";
  static final String NO_APPLIER =
      "not_allowed: remote configuration is not available in this agent context";

  private static final LongSupplier SYSTEM_NANOS =
      new LongSupplier() {
        @Override
        public long getAsLong() {
          return System.nanoTime();
        }
      };

  private static final int COMMAND = 0;
  private static final int PUSH = 1;
  private static final int HELLO = 2;

  /** One queued unit of work. */
  private static final class Task {
    final int kind;
    final Command command;
    final ConfigPush push;
    final ResultSink sink;
    final long receivedNanos;

    Task(int kind, Command command, ConfigPush push, ResultSink sink, long receivedNanos) {
      this.kind = kind;
      this.command = command;
      this.push = push;
      this.sink = sink;
      this.receivedNanos = receivedNanos;
    }
  }

  private final Map<CommandType, CommandHandler> handlers;
  private final ConfigPushApplier applier;
  private final Cooldown cooldown;
  private final LongSupplier nanoClock;
  private final ThreadFactory threads;
  private final Object lock = new Object();

  // Under lock.
  private final ArrayDeque<Task> queue = new ArrayDeque<Task>();
  private int queuedCommands;
  private int queuedConfig;
  private Map<String, String> pendingHello;
  private boolean helloQueued;
  private Thread thread;
  private boolean stopped;

  // argos-command only.
  private boolean workerFailed;

  /**
   * @param handlers the handler of each command type (types without one are {@code REJECTED})
   * @param applier the configuration apply path, or null (every pushed key {@code NOT_ALLOWED})
   * @param config the configuration in force ({@code commands.thread-dump.cooldown-seconds})
   * @param nanoClock {@code System::nanoTime} (TTL, cooldowns, durations); null for that clock
   * @param threads the factory of the {@code argos-command} thread; null runs nothing by itself
   *     (tests call {@link #runPending()})
   */
  public CommandDispatcher(
      Map<CommandType, CommandHandler> handlers,
      ConfigPushApplier applier,
      Supplier<AgentConfig> config,
      LongSupplier nanoClock,
      ThreadFactory threads) {
    EnumMap<CommandType, CommandHandler> copy =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    if (handlers != null) {
      copy.putAll(handlers);
    }
    copy.remove(CommandType.UNRECOGNIZED);
    copy.remove(CommandType.COMMAND_TYPE_UNSPECIFIED);
    this.handlers = Collections.unmodifiableMap(copy);
    this.applier = applier;
    this.cooldown = new Cooldown(config);
    this.nanoClock = nanoClock != null ? nanoClock : SYSTEM_NANOS;
    this.threads = threads;
  }

  // --- RemoteDispatcher (argos-io)
  // -----------------------------------------------------------------

  @Override
  public void command(Command command, ResultSink sink) {
    long now = nanoClock.getAsLong();
    CommandType type = command.getType();
    if (type == CommandType.UNRECOGNIZED || type == CommandType.COMMAND_TYPE_UNSPECIFIED) {
      // SEC-91: a closed list; a newer server's type is not run.
      answer(command, sink, CommandStatus.REJECTED, UNKNOWN_TYPE, 0);
      return;
    }
    if (!handlers.containsKey(type)) {
      answer(command, sink, CommandStatus.REJECTED, NOT_AVAILABLE, 0);
      return;
    }
    boolean busy;
    synchronized (lock) {
      busy = stopped || queuedCommands >= MAX_QUEUED_COMMANDS;
      if (!busy) {
        queuedCommands++;
        enqueue(new Task(COMMAND, command, null, sink, now));
      }
    }
    if (busy) {
      answer(command, sink, CommandStatus.REJECTED, BUSY, 0);
    }
  }

  @Override
  public void configPush(ConfigPush push, ResultSink sink) {
    if (applier == null) {
      sink.send(message(ConfigPushApplier.rejectAll(push, RejectCode.NOT_ALLOWED, NO_APPLIER, "")));
      return;
    }
    boolean busy;
    synchronized (lock) {
      busy = stopped || queuedConfig >= MAX_QUEUED_CONFIG;
      if (!busy) {
        queuedConfig++;
        enqueue(new Task(PUSH, null, push, sink, nanoClock.getAsLong()));
      }
    }
    if (busy) {
      sink.send(
          message(
              ConfigPushApplier.rejectAll(
                  push, RejectCode.LIMIT_EXCEEDED, CONFIG_BUSY, applier.config().configHash())));
    }
  }

  @Override
  public void helloConfig(Map<String, String> config) {
    if (applier == null) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<String, String>();
    if (config != null) {
      values.putAll(config);
    }
    applier.helloKillSwitch(values);
    synchronized (lock) {
      if (stopped) {
        return;
      }
      pendingHello = values;
      if (!helloQueued) {
        helloQueued = true;
        enqueue(new Task(HELLO, null, null, null, nanoClock.getAsLong()));
      }
    }
  }

  /** Under lock: queues {@code task}, starts the thread on first use and wakes it. */
  private void enqueue(Task task) {
    queue.addLast(task);
    if (thread == null && threads != null) {
      Thread worker =
          threads.newThread(
              new Runnable() {
                @Override
                public void run() {
                  work();
                }
              });
      thread = worker;
      worker.start();
    }
    lock.notifyAll();
  }

  // --- argos-command
  // -------------------------------------------------------------------------------

  private void work() {
    while (true) {
      Task task;
      synchronized (lock) {
        while (queue.isEmpty()) {
          if (stopped) {
            return;
          }
          try {
            lock.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        task = take();
      }
      run(task);
    }
  }

  /** Runs every queued task on the calling thread (tests without a thread). */
  void runPending() {
    while (true) {
      Task task;
      synchronized (lock) {
        if (queue.isEmpty()) {
          return;
        }
        task = take();
      }
      run(task);
    }
  }

  /** Under lock: the next task, counters updated. */
  private Task take() {
    Task task = queue.pollFirst();
    if (task.kind == COMMAND) {
      queuedCommands--;
    } else if (task.kind == PUSH) {
      queuedConfig--;
    }
    return task;
  }

  /** Stops the thread once the queue is empty (tests, shutdown); later messages are refused. */
  public void stop() {
    synchronized (lock) {
      stopped = true;
      lock.notifyAll();
    }
  }

  /** True once the {@code argos-command} thread was started (tests). */
  boolean threadStarted() {
    synchronized (lock) {
      return thread != null;
    }
  }

  private void run(Task task) {
    try {
      switch (task.kind) {
        case COMMAND:
          execute(task);
          break;
        case PUSH:
          ConfigApplyResult result = applier.apply(task.push);
          task.sink.send(message(result));
          break;
        default:
          Map<String, String> hello;
          synchronized (lock) {
            hello = pendingHello;
            pendingHello = null;
            helloQueued = false;
          }
          if (hello != null) {
            applier.applyHello(hello);
          }
          break;
      }
    } catch (Throwable t) {
      // The thread outlives any failure; a push that failed here gets no answer (the server times
      // it out), so say so once.
      if (!workerFailed) {
        workerFailed = true;
        AgentLogger.warn("remote command or configuration task failed: {}", t.getClass().getName());
      }
    }
  }

  private void execute(Task task) {
    Command command = task.command;
    CommandType type = command.getType();
    long start = nanoClock.getAsLong();
    long ttlSeconds = command.getTtlS() & 0xFFFF_FFFFL;
    long ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds == 0 ? DEFAULT_TTL_SECONDS : ttlSeconds);
    if (start - task.receivedNanos > ttlNanos) {
      answer(command, task.sink, CommandStatus.EXPIRED, EXPIRED, 0);
      return;
    }
    if (!cooldown.tryStart(type, start)) {
      answer(
          command,
          task.sink,
          CommandStatus.COOLDOWN,
          "cooldown: "
              + type.name()
              + " may run again in "
              + cooldown.remainingSeconds(type, start)
              + " s",
          0);
      return;
    }
    CommandHandler handler = handlers.get(type);
    Map<String, String> args = new HashMap<String, String>(command.getArgsMap());
    args.put(CommandHandler.COMMAND_ID_ARG, command.getCommandId());
    CommandOutput output;
    try {
      output = handler.run(args);
    } catch (Throwable t) {
      answer(command, task.sink, CommandStatus.FAILED, failure(t), millisSince(start));
      return;
    }
    long durationMs = millisSince(start);
    for (CommandResult chunk : chunks(command.getCommandId(), output, durationMs)) {
      task.sink.send(AgentMessage.newBuilder().setCommandResult(chunk).build());
    }
    logCommand(type.getNumber(), CommandStatus.OK, durationMs);
  }

  private long millisSince(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoClock.getAsLong() - startNanos));
  }

  /**
   * The chunks of {@code output}: payload cut at {@value #MAX_PAYLOAD_BYTES} bytes ({@code
   * truncated}), chunks of at most {@value #CHUNK_BYTES} bytes, at least one chunk.
   */
  static List<CommandResult> chunks(String commandId, CommandOutput output, long durationMs) {
    byte[] payload = output == null ? new byte[0] : output.payload();
    boolean truncated = output != null && output.truncated();
    int length = payload.length;
    if (length > MAX_PAYLOAD_BYTES) {
      length = MAX_PAYLOAD_BYTES;
      truncated = true;
    }
    int count = Math.max(1, (length + CHUNK_BYTES - 1) / CHUNK_BYTES);
    String contentType = output == null || output.contentType() == null ? "" : output.contentType();
    List<CommandResult> chunks = new ArrayList<CommandResult>(count);
    for (int i = 0; i < count; i++) {
      int from = i * CHUNK_BYTES;
      int to = Math.min(length, from + CHUNK_BYTES);
      chunks.add(
          CommandResult.newBuilder()
              .setCommandId(commandId)
              .setStatus(CommandStatus.OK)
              .setChunkIndex(i)
              .setChunkCount(count)
              .setPayload(com.google.protobuf.ByteString.copyFrom(payload, from, to - from))
              .setContentType(contentType)
              .setDurationMs((int) Math.min(Integer.MAX_VALUE, durationMs))
              .setTruncated(truncated)
              .build());
    }
    return chunks;
  }

  /**
   * {@code CommandResult.error} of a handler failure: the exception's simple class name and its
   * message on one line, at most {@value #MAX_ERROR_BYTES} UTF-8 bytes, never a stack trace.
   */
  static String failure(Throwable error) {
    String message = error.getMessage();
    String text =
        "failed: "
            + error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
    StringBuilder line = new StringBuilder(Math.min(text.length(), MAX_ERROR_BYTES));
    int bytes = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n' || c == '\t') {
        c = ' ';
      }
      int size = c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
      if (bytes + size > MAX_ERROR_BYTES) {
        break;
      }
      line.append(c);
      bytes += size;
    }
    int last = line.length() - 1;
    if (last >= 0 && Character.isHighSurrogate(line.charAt(last))) {
      line.setLength(last);
    }
    return line.toString();
  }

  private void answer(
      Command command, ResultSink sink, CommandStatus status, String error, long durationMs) {
    sink.send(
        AgentMessage.newBuilder()
            .setCommandResult(
                CommandResult.newBuilder()
                    .setCommandId(command.getCommandId())
                    .setStatus(status)
                    .setChunkIndex(0)
                    .setChunkCount(1)
                    .setError(error)
                    .setDurationMs((int) Math.min(Integer.MAX_VALUE, durationMs)))
            .build());
    logCommand(command.getTypeValue(), status, durationMs);
  }

  /** One INFO line per command: type number, status, milliseconds (never the content). */
  private static void logCommand(int type, CommandStatus status, long durationMs) {
    AgentLogger.info("remote command type {}: {} in {} ms", type, status, durationMs);
  }

  private static AgentMessage message(ConfigApplyResult result) {
    return AgentMessage.newBuilder().setConfigApplyResult(result).build();
  }

  /** Queued tasks (tests). */
  int queued() {
    synchronized (lock) {
      return queue.size();
    }
  }

  /** The handlers (tests). */
  Map<CommandType, CommandHandler> handlers() {
    return handlers;
  }
}
