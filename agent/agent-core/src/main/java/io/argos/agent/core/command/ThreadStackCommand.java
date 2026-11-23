/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.TraceRuntime;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code THREAD_STACK} (10 §12.1, 11 §4.2): the stack of one thread, as the same JSON as {@link
 * ThreadDumpCommand} with one thread. Exactly one argument: {@code tx_id} (unsigned decimal, the
 * thread that runs that transaction now, virtual threads included) or {@code thread_id} (decimal; a
 * thread that runs a transaction, or a platform thread). Both, neither or a malformed value fail
 * with {@value #INVALID_ARGUMENTS}; a transaction that is not running fails with {@value
 * #NOT_RUNNING}; an unknown thread with {@value #THREAD_NOT_FOUND}.
 *
 * <p>Frames: {@code Thread.getStackTrace()} (works for virtual threads), at most {@value
 * ThreadDumpJson#MAX_FRAMES}; a platform thread is read with {@code ThreadMXBean} so that its locks
 * are included.
 */
final class ThreadStackCommand implements CommandHandler {
  static final String TX_ID = "tx_id";
  static final String THREAD_ID = "thread_id";
  static final String INVALID_ARGUMENTS = "invalid arguments";
  static final String NOT_RUNNING = "transaction not running";
  static final String THREAD_NOT_FOUND = "thread not found";

  /** Result cap (one thread is far below it). */
  static final int MAX_BYTES = 256 * 1024;

  private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,20}");

  private final CommandEnv env;

  ThreadStackCommand(CommandEnv env) {
    this.env = env;
  }

  @Override
  public CommandOutput run(Map<String, String> args) throws Exception {
    String tx = args == null ? null : args.get(TX_ID);
    String threadArg = args == null ? null : args.get(THREAD_ID);
    if ((tx == null) == (threadArg == null)) {
      throw new IllegalArgumentException(INVALID_ARGUMENTS);
    }
    long value = parse(tx != null ? tx : threadArg);
    TraceRuntime runtime = env.runtime();
    ThreadDumpJson.ThreadEntry entry;
    if (tx != null) {
      TraceContext context = runtime == null ? null : runtime.activeRegistry().find(value);
      Thread thread = threadOf(context);
      if (thread == null) {
        throw new IllegalStateException(NOT_RUNNING);
      }
      entry = entry(thread, context.virtualThread).withTx(context.txId);
    } else {
      TraceContext context = contextOnThread(runtime, value);
      Thread thread = threadOf(context);
      if (thread != null) {
        entry = entry(thread, context.virtualThread).withTx(context.txId);
      } else {
        entry = platform(value);
        if (entry == null) {
          throw new IllegalStateException(THREAD_NOT_FOUND);
        }
      }
    }
    ThreadDumpJson.Encoded encoded =
        ThreadDumpJson.encode(
            Collections.singletonList(entry),
            Collections.<long[]>emptyList(),
            null,
            false,
            MAX_BYTES);
    return CommandOutput.json(encoded.utf8, encoded.truncated);
  }

  /** An unsigned decimal id; anything else is {@value #INVALID_ARGUMENTS}. */
  static long parse(String text) {
    String trimmed = text.trim();
    if (!DECIMAL.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(INVALID_ARGUMENTS);
    }
    try {
      return Long.parseUnsignedLong(trimmed);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(INVALID_ARGUMENTS, e);
    }
  }

  private static Thread threadOf(TraceContext context) {
    if (context == null || context.isEnded() || context.thread == null) {
      return null;
    }
    return context.thread.get();
  }

  /** The running transaction on thread {@code threadId}, or null. */
  private static TraceContext contextOnThread(TraceRuntime runtime, final long threadId) {
    if (runtime == null) {
      return null;
    }
    final TraceContext[] found = new TraceContext[1];
    runtime
        .activeRegistry()
        .forEach(
            (TraceContext context) -> {
              Thread thread = threadOf(context);
              if (found[0] == null && thread != null && thread.getId() == threadId) {
                found[0] = context;
              }
            });
    return found[0];
  }

  /** {@code thread}'s stack; a platform thread through {@code ThreadMXBean} (locks included). */
  static ThreadDumpJson.ThreadEntry entry(Thread thread, boolean virtual) {
    if (!virtual) {
      ThreadDumpJson.ThreadEntry platform = platform(thread.getId());
      if (platform != null) {
        return platform;
      }
    }
    StackTraceElement[] stack = thread.getStackTrace();
    Thread.State state = thread.getState();
    return new ThreadDumpJson.ThreadEntry(
        thread.getId(),
        thread.getName(),
        state == null ? null : state.name(),
        virtual,
        new ArrayList<String>(0),
        null,
        ThreadDumpCommand.frames(stack),
        false,
        0L);
  }

  /** A platform thread by id with locks where supported, or null when there is none. */
  static ThreadDumpJson.ThreadEntry platform(long threadId) {
    if (threadId <= 0) {
      return null;
    }
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    ThreadInfo[] infos;
    boolean monitors = bean.isObjectMonitorUsageSupported();
    boolean synchronizers = bean.isSynchronizerUsageSupported();
    long[] ids = {threadId};
    if (monitors || synchronizers) {
      infos = bean.getThreadInfo(ids, monitors, synchronizers);
    } else {
      infos = bean.getThreadInfo(ids, ThreadDumpJson.MAX_FRAMES);
    }
    List<ThreadInfo> found = new ArrayList<ThreadInfo>(1);
    for (ThreadInfo info : infos) {
      if (info != null) {
        found.add(info);
      }
    }
    return found.isEmpty() ? null : ThreadDumpCommand.platformEntry(found.get(0));
  }
}
