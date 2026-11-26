/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.proto.v1.RejectReason;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.load.PrometheusText;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The unauthenticated Hello flood of 18 §8 (T-SEC-15, SEC-05, WO-29 step A2): 100 Hellos with
 * unknown tokens from the loopback address; the first 20 get {@code TOKEN_INVALID}, then the
 * address is blocked for 10 minutes ({@code RESOURCE_EXHAUSTED}, trailer reason {@code
 * auth_blocked}, {@code argos_collector_auth_blocked_total}). Every fake agent of the run shares
 * that address, so this phase runs last, after the load, the end check and the idle sessions; the
 * server's resident set growth is recorded as well.
 */
final class UnauthFloodPhase implements LoadPhase {

  /** SEC-05: failed Hellos per minute from one address before it is blocked. */
  static final int CHECKED_BEFORE_BLOCK = 20;

  private final List<ReportSection> sections = new ArrayList<>();
  private final List<Criterion> criteria = new ArrayList<>();

  @Override
  public String name() {
    return "unauth-flood";
  }

  @Override
  public void underLoad(LoadContext context) {}

  @Override
  public void afterLoad(LoadContext c) throws Exception {
    if (!c.options().attacks().contains("unauth-flood")) {
      return;
    }
    int attempts = Attacks.UNAUTHENTICATED_HELLOS_PER_MINUTE;
    PrometheusText before = PhaseSupport.scrape(c);
    long rssBefore = c.rss().sampleNow().orElse(-1);
    List<HelloResult> results;
    long start = System.nanoTime();
    ManagedChannel channel = PhaseSupport.channel(c, AgentChannels.Options.defaults());
    try {
      results =
          Attacks.unauthenticatedHelloFlood(
              i ->
                  FakeAgent.builder(channel)
                      .token(AgentTokens.generate())
                      .appName("unauth-flood")
                      .instanceName("unauth-" + i)
                      .helloTimeout(Duration.ofSeconds(10))
                      .build(),
              attempts,
              Duration.ZERO);
    } finally {
      channel.shutdownNow();
    }
    long millis = (System.nanoTime() - start) / 1_000_000;
    long rssAfter = c.rss().sampleNow().orElse(-1);
    PrometheusText after = PhaseSupport.scrape(c);
    long checked =
        results.stream().filter(h -> h.rejectReason() == RejectReason.TOKEN_INVALID).count();
    long blocked =
        results.stream()
            .filter(
                h ->
                    h.hello() == null
                        && h.code() == Status.Code.RESOURCE_EXHAUSTED
                        && "auth_blocked".equals(h.trailerRejectReason()))
            .count();
    boolean order =
        results.size() == attempts
            && results.subList(0, CHECKED_BEFORE_BLOCK).stream()
                .allMatch(h -> h.rejectReason() == RejectReason.TOKEN_INVALID)
            && results.subList(CHECKED_BEFORE_BLOCK, attempts).stream()
                .allMatch(
                    h ->
                        h.hello() == null
                            && h.code() == Status.Code.RESOURCE_EXHAUSTED
                            && "auth_blocked".equals(h.trailerRejectReason()));
    double blockedCounter =
        after.sum("argos_collector_auth_blocked_total")
            - before.sum("argos_collector_auth_blocked_total");
    String measured =
        String.format(
            Locale.ROOT,
            "%d TOKEN_INVALID, %d blocked (auth_blocked), argos_collector_auth_blocked_total +%.0f,"
                + " in %d ms",
            checked,
            blocked,
            blockedCounter,
            millis);
    criteria.add(
        PhaseSupport.invariant(
            "T-SEC-15",
            "SEC-05, SEC-15",
            "unauthenticated Hello flood blocked after 20 failures",
            "20 TOKEN_INVALID, then RESOURCE_EXHAUSTED auth_blocked; counter +80",
            measured,
            !results.isEmpty(),
            order && Math.round(blockedCounter) == attempts - CHECKED_BEFORE_BLOCK,
            "18 §8 unauth-flood, 100 Hellos with unknown tokens from 127.0.0.1"));
    long growth = rssBefore < 0 || rssAfter < 0 ? -1 : rssAfter - rssBefore;
    StringBuilder md = new StringBuilder();
    md.append("| Item | Value |\n|---|---|\n");
    md.append("| Hellos | ").append(attempts).append(" with unknown tokens, back to back |\n");
    md.append("| Outcome | ").append(Format.cell(measured)).append(" |\n");
    md.append("| argos_collector_rejected_total increase by reason | ")
        .append(
            Format.cell(
                PhaseSupport.counts(
                    AttackPhase.delta(before, after, "argos_collector_rejected_total"))))
        .append(" |\n");
    md.append("| Server resident set | ")
        .append(
            Format.cell(
                Format.bytes(rssBefore)
                    + " -> "
                    + Format.bytes(rssAfter)
                    + " ("
                    + PhaseSupport.signedBytes(growth < 0 ? Long.MIN_VALUE : growth)
                    + ")"))
        .append(" |\n");
    md.append(
        "\nRun last: the block covers the loopback address of every fake agent for 10 minutes."
            + " A run with a separate load generator (18 §2.1, follow-up) can send it during the"
            + " load from another address.\n");
    sections.add(new ReportSection("Unauthenticated Hello flood (T-SEC-15)", md.toString()));
    if (!order) {
      List<String> examples = new ArrayList<>();
      for (int i = 0; i < results.size() && examples.size() < 5; i++) {
        HelloResult h = results.get(i);
        boolean expected =
            i < CHECKED_BEFORE_BLOCK
                ? h.rejectReason() == RejectReason.TOKEN_INVALID
                : h.code() == Status.Code.RESOURCE_EXHAUSTED;
        if (!expected) {
          examples.add(
              "#" + i + " " + h + " trailer " + h.trailers().get(CallResult.REJECT_REASON));
        }
      }
      LoadRunner.log("unauth-flood unexpected outcomes: " + examples);
    }
  }

  @Override
  public List<ReportSection> sections() {
    return List.copyOf(sections);
  }

  @Override
  public List<Criterion> criteria() {
    return List.copyOf(criteria);
  }
}
