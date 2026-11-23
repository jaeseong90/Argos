/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.httpclient.HttpClientRuns.Call;
import io.argos.agent.testing.httpclient.HttpClientRuns.Combo;
import io.argos.agent.testing.httpclient.HttpClientRuns.Profile;
import io.argos.agent.testing.httpclient.HttpClientRuns.Run;
import io.argos.sample.common.http.PayService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The W3C headers that really left the app (ext-stub's request log), for every client of every shop
 * jar app of {@code -Pagent.e2e} (10 §8, F-A06, F-A32, SEC-38):
 *
 * <ul>
 *   <li>T-AG-057: a {@code traceparent} the application set itself ({@code preset=1}) reaches
 *       ext-stub unchanged and the step detail records it; the application's {@code tracestate}
 *       vendor entry ({@value PayService#PRESET_TRACESTATE}) is kept (with {@code
 *       argos-state.hosts}, after the {@code argos=} entry). An incoming {@code traceparent} gives
 *       the transaction its trace id and parent span, and the outgoing call carries that trace id
 *       and the incoming {@code tracestate}; an incoming {@code argos=1:2:3} entry gives {@code
 *       caller_app_id} 1 and is replaced, never duplicated, on the way out.
 *   <li>T-SEC-38: by default no {@code tracestate} (no {@code argos=} entry) goes out; {@code
 *       propagation.argos-state.hosts=127.0.0.1} adds {@code argos=<app>:<instance>:<service>} with
 *       the app id of the run; {@code propagation.exclude-hosts=127.0.0.*} sends neither header
 *       (the step is still recorded, the application's own headers still go out).
 * </ul>
 */
@Tag("req:F-A06")
@Tag("req:F-A32")
@Tag("req:SEC-38")
@ExtendWith(HttpClientRuns.class)
class PropagationHeadersTest {

  /** An incoming W3C context of the inbound-adoption checks. */
  static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  static final String INBOUND_SPAN_ID = "00f067aa0ba902b7";

  static final String INBOUND_TRACEPARENT =
      "00-" + INBOUND_TRACE_ID + "-" + INBOUND_SPAN_ID + "-01";

  /**
   * Clients whose framework applies the application's request headers after the agent's
   * (httpclient-reactornetty README: Spring WebClient's ClientRequest headers). Their kept
   * traceparent is not in the step detail and an application tracestate gets no argos entry; the
   * WO-14 report lists this as an open issue.
   */
  static final Set<String> HEADERS_APPLIED_AFTER_AGENT = Set.of("webclient");

  @TestFactory
  @DisplayName("T-AG-057 / T-SEC-38 headers at ext-stub per app and client (10 §8, SEC-38)")
  Stream<DynamicNode> perClient() {
    List<DynamicNode> nodes = new ArrayList<>();
    for (Combo combo : HttpClientRuns.jarCombos()) {
      List<DynamicNode> tests = new ArrayList<>();
      for (String client : HttpClientStepsTest.CLIENTS.get(combo.app())) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-SEC-38 "
                    + combo
                    + " "
                    + client
                    + ": default configuration sends traceparent and no tracestate (no argos"
                    + " entry)",
                () -> defaults(HttpClientRuns.run(combo, Profile.DEFAULT), client)));
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-057 "
                    + combo
                    + " "
                    + client
                    + ": the application's traceparent and tracestate are kept; the incoming"
                    + " context is adopted and forwarded",
                () -> preserved(HttpClientRuns.run(combo, Profile.DEFAULT), client)));
        tests.add(
            DynamicTest.dynamicTest(
                "T-SEC-38 "
                    + combo
                    + " "
                    + client
                    + ": argos-state.hosts=127.0.0.1 adds argos=<app>:<instance>:<service>"
                    + " first, replacing an incoming argos entry",
                () -> argosState(HttpClientRuns.run(combo, Profile.ARGOS_STATE), client)));
        tests.add(
            DynamicTest.dynamicTest(
                "T-SEC-38 "
                    + combo
                    + " "
                    + client
                    + ": exclude-hosts=127.0.0.* sends neither header (step still recorded)",
                () -> excluded(HttpClientRuns.run(combo, Profile.EXCLUDE), client)));
      }
      nodes.add(DynamicContainer.dynamicContainer(combo.toString(), tests.stream()));
    }
    return nodes.stream();
  }

  /** T-SEC-38, default part. */
  static void defaults(Run run, String client) throws Exception {
    long id = HttpClientRuns.nextId();
    Call call = run.call("/api/pay/" + id + "?client=" + client, 200);
    Map<String, String> headers = run.stubHeaders("/pay/" + id);
    assertThat(headers.get("traceparent"))
        .as("traceparent from %s", client)
        .isNotNull()
        .startsWith("00-" + call.tx().traceId() + "-");
    assertThat(headers)
        .as("SEC-38: no tracestate (argos entry) by default from %s", client)
        .doesNotContainKey("tracestate");

    // An upstream argos entry is read (caller_app_id) but not passed on to a host outside
    // argos-state.hosts: the upstream app id does not leak (SEC-38).
    long inboundId = HttpClientRuns.nextId();
    Call inbound =
        run.call(
            "/api/pay/" + inboundId + "?client=" + client,
            Map.of("traceparent", INBOUND_TRACEPARENT, "tracestate", "argos=1:2:3,vendor=inbound"),
            200);
    assertThat(inbound.tx().field("caller_app_id"))
        .as("caller_app_id from the incoming argos entry")
        .isEqualTo(1L);
    assertThat(run.stubHeaders("/pay/" + inboundId).get("tracestate"))
        .as("SEC-38: the incoming argos entry is not forwarded by default (%s)", client)
        .isEqualTo("vendor=inbound");
  }

  /** T-AG-057. */
  static void preserved(Run run, String client) throws Exception {
    // The application's own headers.
    long id = HttpClientRuns.nextId();
    Call preset = run.call("/api/pay/" + id + "?client=" + client + "&preset=1", 200);
    Object own = preset.body().get("presetTraceparent");
    assertThat(own).as("presetTraceparent in %s", preset.body()).isInstanceOf(String.class);
    Map<String, String> headers = run.stubHeaders("/pay/" + id);
    assertThat(headers.get("traceparent"))
        .as("10 §8: the application's traceparent is kept (%s)", client)
        .isEqualTo(own);
    assertThat(headers.get("tracestate"))
        .as("the application's tracestate is kept, without an argos entry (%s)", client)
        .isEqualTo(PayService.PRESET_TRACESTATE);
    StepRecord step = preset.onlyHttpStep(client + " preset");
    if (HEADERS_APPLIED_AFTER_AGENT.contains(client)) {
      // Known gap (httpclient-reactornetty README): Spring applies the ClientRequest headers after
      // the agent's, so the agent never saw the application's traceparent.
      assertThat(step.detail())
          .as("documented WebClient gap: the kept traceparent is not in the detail (%s)", client)
          .doesNotContain((String) own);
    } else {
      assertThat(step.detail())
          .as("10 §8: the kept traceparent is recorded in the step detail (%s)", client)
          .contains((String) own);
    }

    // The incoming context: adopted by the servlet entry, forwarded on the outgoing call.
    long inboundId = HttpClientRuns.nextId();
    Call inbound =
        run.call(
            "/api/pay/" + inboundId + "?client=" + client,
            Map.of("traceparent", INBOUND_TRACEPARENT, "tracestate", "vendor=inbound"),
            200);
    assertThat(inbound.tx().traceId()).as("adopted trace id").isEqualTo(INBOUND_TRACE_ID);
    assertThat(inbound.tx().field("parent_span_id")).as("parent span").isEqualTo(INBOUND_SPAN_ID);
    assertThat(inbound.tx().field("incoming_trace")).isEqualTo(true);
    assertThat(inbound.tx().field("span_id")).isNotEqualTo(INBOUND_SPAN_ID);
    Map<String, String> forwarded = run.stubHeaders("/pay/" + inboundId);
    assertThat(forwarded.get("traceparent"))
        .as("outgoing traceparent continues the incoming trace (%s)", client)
        .startsWith("00-" + INBOUND_TRACE_ID + "-")
        .doesNotContain(INBOUND_SPAN_ID);
    assertThat(forwarded.get("tracestate"))
        .as("the incoming tracestate is forwarded (%s)", client)
        .isEqualTo("vendor=inbound");
  }

  /** T-SEC-38, argos-state.hosts part (and the argos entry part of T-AG-057). */
  static void argosState(Run run, String client) throws Exception {
    long id = HttpClientRuns.nextId();
    run.call("/api/pay/" + id + "?client=" + client, 200);
    String state = run.stubHeaders("/pay/" + id).get("tracestate");
    String appHex = Integer.toHexString(V1Protocol.appIdOf(run.appName()));
    assertThat(state)
        .as("SEC-38: argos entry for a host of argos-state.hosts (%s)", client)
        .isNotNull()
        .matches(HttpClientRuns.ARGOS_ENTRY_PATTERN)
        .startsWith("argos=" + appHex + ":");

    long presetId = HttpClientRuns.nextId();
    run.call("/api/pay/" + presetId + "?client=" + client + "&preset=1", 200);
    String withVendor = run.stubHeaders("/pay/" + presetId).get("tracestate");
    if (HEADERS_APPLIED_AFTER_AGENT.contains(client)) {
      // Known gap (httpclient-reactornetty README): the application's tracestate replaces the
      // agent's value as a whole, so no argos entry is prepended to it.
      assertThat(withVendor)
          .as("documented WebClient gap: the application's tracestate as it was (%s)", client)
          .isEqualTo(PayService.PRESET_TRACESTATE);
    } else {
      assertThat(withVendor)
          .as("argos entry first, the application's vendor entry kept (%s)", client)
          .matches(HttpClientRuns.ARGOS_ENTRY_PATTERN + "," + PayService.PRESET_TRACESTATE);
    }

    long inboundId = HttpClientRuns.nextId();
    Call inbound =
        run.call(
            "/api/pay/" + inboundId + "?client=" + client,
            Map.of(
                "traceparent",
                PropagationHeadersTest.INBOUND_TRACEPARENT,
                "tracestate",
                "argos=1:2:3,vendor=inbound"),
            200);
    assertThat(inbound.tx().field("caller_app_id"))
        .as("caller_app_id from the incoming argos entry")
        .isEqualTo(1L);
    String replaced = run.stubHeaders("/pay/" + inboundId).get("tracestate");
    assertThat(replaced)
        .as("the incoming argos entry is replaced, not duplicated (%s)", client)
        .matches(HttpClientRuns.ARGOS_ENTRY_PATTERN + ",vendor=inbound")
        .startsWith("argos=" + appHex + ":");
  }

  /** T-SEC-38, exclude-hosts part. */
  static void excluded(Run run, String client) throws Exception {
    long id = HttpClientRuns.nextId();
    Call call = run.call("/api/pay/" + id + "?client=" + client, 200);
    Map<String, String> headers = run.stubHeaders("/pay/" + id);
    assertThat(headers)
        .as("SEC-38: no propagation header to an excluded host (%s)", client)
        .doesNotContainKeys("traceparent", "tracestate");
    call.onlyHttpStep(client + " to an excluded host");

    long presetId = HttpClientRuns.nextId();
    Call preset = run.call("/api/pay/" + presetId + "?client=" + client + "&preset=1", 200);
    Map<String, String> own = run.stubHeaders("/pay/" + presetId);
    assertThat(own.get("traceparent"))
        .as("the application's own traceparent still goes out (%s)", client)
        .isEqualTo(preset.body().get("presetTraceparent"));
    assertThat(own.get("tracestate")).isEqualTo(PayService.PRESET_TRACESTATE);
  }
}
