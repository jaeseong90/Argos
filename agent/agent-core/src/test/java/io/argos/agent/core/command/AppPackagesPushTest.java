/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AppPackages;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.QueueItem;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A remote {@code instrumentation.app-packages} (remote Y, 10 §15; here allowed by the local {@code
 * remote-config.allow}) takes effect at run time: errors that end afterwards find their top
 * application frame in the new packages (WO-19, ADR-0010 C-237 (6)); an empty value goes back to
 * the inferred packages. Test ID T-AG-059c is proposed by WO-19.
 */
@Tag("req:F-A08")
@Tag("req:F-A13")
class AppPackagesPushTest {
  @TempDir File dir;
  private RemoteFixture fixture;

  @AfterEach
  void restore() {
    if (fixture != null) {
      fixture.close();
    }
  }

  private static ConfigPush push(String changeId, String key, String value) {
    return ConfigPush.newBuilder().setChangeId(changeId).putValues(key, value).build();
  }

  private String topFrame(TracerImpl tracer) {
    RuntimeException e = new IllegalStateException("x");
    e.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("org.lib.Helper", "check", "Helper.java", 5),
          new StackTraceElement("com.acme.shop.Orders", "place", "Orders.java", 12)
        });
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", 0);
    tracer.end(ctx, 500, null, null, e, 10);
    final List<Transaction> out = new ArrayList<Transaction>();
    AgentQueue<QueueItem> queue = fixture.runtime.queues().transaction();
    queue.drain(
        Integer.MAX_VALUE,
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            out.add((Transaction) item);
          }
        });
    assertThat(out).hasSize(1);
    long id = out.get(0).topAppFrameId();
    return id == 0 ? null : tracer.dictionary().text(id);
  }

  @Test
  @DisplayName(
      "T-AG-059c remote instrumentation.app-packages is applied (not restart_required) and moves"
          + " the top application frame of later errors; [] returns to the inferred packages")
  void remoteChange() throws Exception {
    Map<String, String> properties = RemoteFixture.required();
    properties.put("argos.remote-config.allow", "[instrumentation.app-packages]");
    fixture = new RemoteFixture(dir, properties, "instrumentation:", "  app-packages: [com.acme]");
    TracerImpl tracer = fixture.runtime.tracerImpl();
    tracer.setAppPackagesIfUnset(AppPackages.of(fixture.config.config()).packages());
    assertThat(topFrame(tracer)).isEqualTo("com.acme.shop.Orders.place(Orders.java)");

    ConfigApplyResult changed =
        fixture.pushes.apply(push("c1", "instrumentation.app-packages", "[org.lib]"));
    assertThat(changed.getRejectedList()).isEmpty();
    assertThat(changed.getAppliedKeysList()).containsExactly("instrumentation.app-packages");
    assertThat(topFrame(tracer)).isEqualTo("org.lib.Helper.check(Helper.java)");

    ConfigApplyResult cleared =
        fixture.pushes.apply(push("c2", "instrumentation.app-packages", "[]"));
    assertThat(cleared.getRejectedList()).isEmpty();
    // The test JVM's main class is a launcher (Gradle worker): nothing inferred, so no frame.
    List<String> inferred = AppPackages.of(fixture.config.config()).packages();
    if (inferred.isEmpty()) {
      assertThat(topFrame(tracer)).isNull();
    } else {
      assertThat(inferred).doesNotContain("org.lib");
    }
  }
}
