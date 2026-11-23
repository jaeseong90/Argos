/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.transport.RemoteDispatcher;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The command executor of WO-17 (10 §12.1, 11 §8.2, SEC-24, SEC-91) with a manual clock. */
@Tag("req:SEC-24")
@Tag("req:F-A12")
class CommandDispatcherTest {
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final LongSupplier clock =
      new LongSupplier() {
        @Override
        public long getAsLong() {
          return nanos.get();
        }
      };
  private final Map<String, String> config = new HashMap<String, String>();
  private final Supplier<AgentConfig> configSupplier =
      new Supplier<AgentConfig>() {
        @Override
        public AgentConfig get() {
          return AgentConfig.of(config);
        }
      };
  private final List<Map<String, String>> calls = new ArrayList<Map<String, String>>();

  /** Collects what the dispatcher answers. */
  static final class Sink implements RemoteDispatcher.ResultSink {
    final List<AgentMessage> messages = new CopyOnWriteArrayList<AgentMessage>();

    @Override
    public void send(AgentMessage message) {
      messages.add(message);
    }

    List<CommandResult> results() {
      List<CommandResult> out = new ArrayList<CommandResult>();
      for (AgentMessage message : messages) {
        if (message.hasCommandResult()) {
          out.add(message.getCommandResult());
        }
      }
      return out;
    }

    CommandResult only() {
      assertThat(results()).hasSize(1);
      return results().get(0);
    }
  }

  private CommandHandler recording(final CommandOutput output) {
    return new CommandHandler() {
      @Override
      public CommandOutput run(Map<String, String> args) {
        calls.add(new HashMap<String, String>(args));
        return output;
      }
    };
  }

  private CommandDispatcher dispatcher(Map<CommandType, CommandHandler> handlers) {
    return new CommandDispatcher(handlers, null, configSupplier, clock, null);
  }

  private CommandDispatcher withAllTypes() {
    Map<CommandType, CommandHandler> handlers =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    CommandHandler ok = recording(CommandOutput.json(utf8("{\"ok\":true}"), false));
    for (CommandType type :
        new CommandType[] {
          CommandType.THREAD_DUMP,
          CommandType.THREAD_STACK,
          CommandType.SELF_DIAGNOSTICS,
          CommandType.CONFIG_RELOAD,
          CommandType.ENV_DUMP
        }) {
      handlers.put(type, ok);
    }
    return dispatcher(handlers);
  }

  private static Command command(String id, CommandType type, int ttlSeconds) {
    return Command.newBuilder()
        .setCommandId(id)
        .setType(type)
        .setTtlS(ttlSeconds)
        // issued an hour ago by the server's clock: the TTL is measured from the receipt here.
        .setIssuedAtMs(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1))
        .build();
  }

  private void advanceSeconds(long seconds) {
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "T-SEC-24 TTL from the receipt: a command whose issued_at is an hour old still runs; one not"
          + " started within ttl_s of its receipt is EXPIRED and its handler never runs")
  void ttlFromReceipt() {
    CommandDispatcher dispatcher = withAllTypes();
    Sink fresh = new Sink();
    dispatcher.command(command("c-1", CommandType.SELF_DIAGNOSTICS, 5), fresh);
    advanceSeconds(4);
    dispatcher.runPending();
    assertThat(fresh.only().getStatus()).isEqualTo(CommandStatus.OK);

    Sink late = new Sink();
    dispatcher.command(command("c-2", CommandType.THREAD_STACK, 5), late);
    advanceSeconds(6);
    dispatcher.runPending();
    assertThat(late.only().getStatus()).isEqualTo(CommandStatus.EXPIRED);
    assertThat(late.only().getError()).startsWith("expired:");
    assertThat(calls).hasSize(1);
  }

  @Test
  @DisplayName("T-SEC-24 ttl_s 0 means 60 s: started after 59 s it runs, after 61 s it expires")
  void defaultTtl() {
    CommandDispatcher dispatcher = withAllTypes();
    Sink first = new Sink();
    dispatcher.command(command("c-1", CommandType.SELF_DIAGNOSTICS, 0), first);
    advanceSeconds(59);
    dispatcher.runPending();
    assertThat(first.only().getStatus()).isEqualTo(CommandStatus.OK);

    Sink second = new Sink();
    advanceSeconds(10);
    dispatcher.command(command("c-2", CommandType.THREAD_STACK, 0), second);
    advanceSeconds(61);
    dispatcher.runPending();
    assertThat(second.only().getStatus()).isEqualTo(CommandStatus.EXPIRED);
  }

  @Test
  @DisplayName(
      "T-SEC-24/T-SEC-24e cooldowns: THREAD_DUMP commands.thread-dump.cooldown-seconds, THREAD_STACK 1 s,"
          + " SELF_DIAGNOSTICS 1 s, ENV_DUMP 60 s, CONFIG_RELOAD 60 s; a cooled-down command does"
          + " not run")
  void cooldowns() {
    config.put("commands.thread-dump.cooldown-seconds", "7");
    CommandDispatcher dispatcher = withAllTypes();
    long[][] expected = {
      {CommandType.THREAD_DUMP_VALUE, 7},
      {CommandType.THREAD_STACK_VALUE, 1},
      {CommandType.SELF_DIAGNOSTICS_VALUE, 1},
      {CommandType.ENV_DUMP_VALUE, 60},
      {CommandType.CONFIG_RELOAD_VALUE, 60}
    };
    int id = 0;
    for (long[] row : expected) {
      CommandType type = CommandType.forNumber((int) row[0]);
      long seconds = row[1];
      int before = calls.size();
      Sink first = new Sink();
      dispatcher.command(command("c-" + id++, type, 600), first);
      dispatcher.runPending();
      assertThat(first.only().getStatus()).as(type + " first").isEqualTo(CommandStatus.OK);

      nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds) - 1);
      Sink again = new Sink();
      dispatcher.command(command("c-" + id++, type, 600), again);
      dispatcher.runPending();
      assertThat(again.only().getStatus()).as(type + " within").isEqualTo(CommandStatus.COOLDOWN);
      assertThat(again.only().getError()).startsWith("cooldown: " + type.name());

      nanos.addAndGet(1);
      Sink after = new Sink();
      dispatcher.command(command("c-" + id++, type, 600), after);
      dispatcher.runPending();
      assertThat(after.only().getStatus()).as(type + " after").isEqualTo(CommandStatus.OK);
      assertThat(calls).hasSize(before + 2);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-24 at most 8 commands wait: the 9th is REJECTED busy at once; after the queue drains"
          + " commands are accepted again")
  void busy() {
    CommandDispatcher dispatcher = withAllTypes();
    List<Sink> sinks = new ArrayList<Sink>();
    for (int i = 0; i < CommandDispatcher.MAX_QUEUED_COMMANDS; i++) {
      Sink sink = new Sink();
      sinks.add(sink);
      dispatcher.command(command("c-" + i, CommandType.THREAD_STACK, 600), sink);
    }
    Sink ninth = new Sink();
    dispatcher.command(command("c-9", CommandType.THREAD_STACK, 600), ninth);
    assertThat(ninth.only().getStatus()).isEqualTo(CommandStatus.REJECTED);
    assertThat(ninth.only().getError()).contains("busy");
    for (Sink sink : sinks) {
      assertThat(sink.messages).isEmpty();
    }
    dispatcher.runPending();
    assertThat(sinks.get(0).only().getStatus()).isEqualTo(CommandStatus.OK);
    assertThat(sinks.get(1).only().getStatus())
        .as("one at a time, the same type within its cooldown")
        .isEqualTo(CommandStatus.COOLDOWN);
    Sink later = new Sink();
    dispatcher.command(command("c-10", CommandType.SELF_DIAGNOSTICS, 600), later);
    dispatcher.runPending();
    assertThat(later.only().getStatus()).isEqualTo(CommandStatus.OK);
  }

  @Test
  @Tag("req:SEC-91")
  @DisplayName(
      "T-SEC-24/T-SEC-24e closed list: an unknown type number, an unset type and a type without a handler are"
          + " REJECTED at once with one chunk and no payload; nothing runs")
  void closedList() {
    Map<CommandType, CommandHandler> handlers =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    handlers.put(CommandType.THREAD_DUMP, recording(CommandOutput.text(utf8("x"), false)));
    CommandDispatcher dispatcher = dispatcher(handlers);
    Command[] commands = {
      Command.newBuilder().setCommandId("u").setTypeValue(42).build(),
      Command.newBuilder().setCommandId("z").build(),
      Command.newBuilder().setCommandId("e").setType(CommandType.ENV_DUMP).build()
    };
    for (Command command : commands) {
      Sink sink = new Sink();
      dispatcher.command(command, sink);
      CommandResult result = sink.only();
      assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
      assertThat(result.getChunkCount()).isEqualTo(1);
      assertThat(result.getPayload().isEmpty()).isTrue();
      assertThat(result.getCommandId()).isEqualTo(command.getCommandId());
    }
    assertThat(dispatcher.queued()).isZero();
    dispatcher.runPending();
    assertThat(calls).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-24 a handler failure is FAILED with the exception class and message on one line, at"
          + " most 1 KiB, never a stack trace")
  void failure() {
    StringBuilder longMessage = new StringBuilder("line one\nline two\tend ");
    for (int i = 0; i < 3000; i++) {
      longMessage.append('é');
    }
    final String message = longMessage.toString();
    Map<CommandType, CommandHandler> handlers =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    handlers.put(
        CommandType.THREAD_STACK,
        new CommandHandler() {
          @Override
          public CommandOutput run(Map<String, String> args) {
            throw new IllegalArgumentException(message);
          }
        });
    CommandDispatcher dispatcher = dispatcher(handlers);
    Sink sink = new Sink();
    dispatcher.command(command("c", CommandType.THREAD_STACK, 60), sink);
    dispatcher.runPending();
    CommandResult result = sink.only();
    assertThat(result.getStatus()).isEqualTo(CommandStatus.FAILED);
    assertThat(result.getError()).startsWith("failed: IllegalArgumentException: line one line two");
    assertThat(result.getError())
        .doesNotContain("\n")
        .doesNotContain("\tat ")
        .doesNotContain("at io.");
    assertThat(result.getError().getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(CommandDispatcher.MAX_ERROR_BYTES);
    assertThat(result.getPayload().isEmpty()).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-24 results: chunks of at most 256 KiB in order with content type and duration, the"
          + " total cut at 1 MiB with truncated; an empty payload is one chunk")
  void chunking() {
    byte[] medium = new byte[600 * 1024];
    for (int i = 0; i < medium.length; i++) {
      medium[i] = (byte) ('a' + i % 26);
    }
    List<CommandResult> three =
        CommandDispatcher.chunks("m", CommandOutput.text(medium, false), 12);
    assertThat(three).hasSize(3);
    assertThat(joined(three)).isEqualTo(medium);
    for (int i = 0; i < three.size(); i++) {
      CommandResult chunk = three.get(i);
      assertThat(chunk.getChunkIndex()).isEqualTo(i);
      assertThat(chunk.getChunkCount()).isEqualTo(3);
      assertThat(chunk.getPayload().size()).isLessThanOrEqualTo(CommandDispatcher.CHUNK_BYTES);
      assertThat(chunk.getContentType()).isEqualTo("text/plain");
      assertThat(chunk.getDurationMs()).isEqualTo(12);
      assertThat(chunk.getTruncated()).isFalse();
      assertThat(chunk.getStatus()).isEqualTo(CommandStatus.OK);
    }

    byte[] big = new byte[CommandDispatcher.MAX_PAYLOAD_BYTES + 500_000];
    List<CommandResult> capped = CommandDispatcher.chunks("b", CommandOutput.json(big, false), 1);
    assertThat(capped).hasSize(4);
    assertThat(joined(capped)).hasSize(CommandDispatcher.MAX_PAYLOAD_BYTES);
    assertThat(capped).allSatisfy(chunk -> assertThat(chunk.getTruncated()).isTrue());

    List<CommandResult> handlerCut =
        CommandDispatcher.chunks("t", CommandOutput.json(utf8("{}"), true), 1);
    assertThat(handlerCut).hasSize(1);
    assertThat(handlerCut.get(0).getTruncated()).isTrue();

    List<CommandResult> empty =
        CommandDispatcher.chunks("e", CommandOutput.text(new byte[0], false), 0);
    assertThat(empty).hasSize(1);
    assertThat(empty.get(0).getChunkCount()).isEqualTo(1);
  }

  private static byte[] joined(List<CommandResult> chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (CommandResult chunk : chunks) {
      byte[] bytes = chunk.getPayload().toByteArray();
      out.write(bytes, 0, bytes.length);
    }
    return out.toByteArray();
  }

  @Test
  @DisplayName(
      "T-SEC-24 handlers get the server's arguments plus the command id, never a modified server"
          + " map")
  void arguments() {
    CommandDispatcher dispatcher = withAllTypes();
    Sink sink = new Sink();
    dispatcher.command(
        Command.newBuilder()
            .setCommandId("cmd-7")
            .setType(CommandType.THREAD_STACK)
            .putArgs("tx_id", "123")
            .build(),
        sink);
    dispatcher.runPending();
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0))
        .containsEntry("tx_id", "123")
        .containsEntry(CommandHandler.COMMAND_ID_ARG, "cmd-7")
        .hasSize(2);
    assertThat(sink.only().getCommandId()).isEqualTo("cmd-7");
    assertThat(sink.only().getContentType()).isEqualTo("application/json");
  }

  @Test
  @DisplayName(
      "T-NFR-30a argos-command starts with the first message, not when the dispatcher is built;"
          + " results come back from that thread")
  void lazyThread() throws InterruptedException {
    final List<String> names = new CopyOnWriteArrayList<String>();
    ThreadFactory factory =
        new ThreadFactory() {
          @Override
          public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, CommandDispatcher.THREAD_NAME);
            thread.setDaemon(true);
            names.add(thread.getName());
            return thread;
          }
        };
    Map<CommandType, CommandHandler> handlers =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    final List<String> ranOn = new CopyOnWriteArrayList<String>();
    handlers.put(
        CommandType.SELF_DIAGNOSTICS,
        new CommandHandler() {
          @Override
          public CommandOutput run(Map<String, String> args) {
            ranOn.add(Thread.currentThread().getName());
            return CommandOutput.json(utf8("{}"), false);
          }
        });
    CommandDispatcher dispatcher =
        new CommandDispatcher(handlers, null, configSupplier, clock, factory);
    try {
      assertThat(dispatcher.threadStarted()).isFalse();
      assertThat(names).isEmpty();
      final CountDownLatch answered = new CountDownLatch(1);
      dispatcher.command(
          command("c", CommandType.SELF_DIAGNOSTICS, 60),
          new RemoteDispatcher.ResultSink() {
            @Override
            public void send(AgentMessage message) {
              answered.countDown();
            }
          });
      assertThat(answered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(dispatcher.threadStarted()).isTrue();
      assertThat(names).containsExactly("argos-command");
      assertThat(ranOn).containsExactly("argos-command");
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  @Tag("req:SEC-23")
  @DisplayName(
      "T-SEC-23 without an apply path every pushed key is NOT_ALLOWED; values never appear in the"
          + " answer")
  void pushWithoutApplier() {
    CommandDispatcher dispatcher = withAllTypes();
    Sink sink = new Sink();
    dispatcher.configPush(
        ConfigPush.newBuilder()
            .setChangeId("c-1")
            .putValues("log.level", "DEBUG")
            .putValues("no.such.key", "secret-value-1")
            .build(),
        sink);
    assertThat(sink.messages).hasSize(1);
    ConfigApplyResult result = sink.messages.get(0).getConfigApplyResult();
    assertThat(result.getChangeId()).isEqualTo("c-1");
    assertThat(result.getAppliedKeysList()).isEmpty();
    assertThat(result.getRejectedList())
        .extracting(RejectedKey::getCode)
        .containsOnly(RejectCode.NOT_ALLOWED);
    assertThat(result.toString()).doesNotContain("secret-value-1").doesNotContain("DEBUG");
  }
}
