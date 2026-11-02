/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ignore list (10 §4.5), module discovery (C-25), agent builder factory, loader state. */
class BootTest {
  @TempDir File dir;

  /** Test modules: public, no-arg constructors, as the service file requires. */
  public static class Good implements InstrumentationModule {
    @Override
    public String id() {
      return "good";
    }

    @Override
    public int moduleId() {
      return 40;
    }

    @Override
    public List<TypeInstrumentation> types() {
      return Collections.emptyList();
    }
  }

  public static class Second extends Good {
    @Override
    public String id() {
      return "second";
    }

    @Override
    public int moduleId() {
      return 39;
    }
  }

  public static class DuplicateId extends Good {
    @Override
    public int moduleId() {
      return 45;
    }
  }

  public static class DuplicateModuleId extends Good {
    @Override
    public String id() {
      return "dup";
    }
  }

  public static class InvalidModuleId extends Good {
    @Override
    public String id() {
      return "invalid";
    }

    @Override
    public int moduleId() {
      return 64;
    }
  }

  public static class Failing extends Good {
    public Failing() {
      throw new IllegalStateException("cannot create");
    }
  }

  public static class Off extends Good {
    @Override
    public String id() {
      return "off";
    }

    @Override
    public int moduleId() {
      return 43;
    }
  }

  public static class Faulty extends Good {
    @Override
    public String id() {
      return "faulty";
    }

    @Override
    public int moduleId() {
      return 44;
    }
  }

  @Test
  @Tag("req:N-27")
  @DisplayName(
      "ignore list: Argos, JDK (except the allow-list), generated classes, agents, logging")
  void ignoreList() {
    List<Pattern> none = Collections.emptyList();
    for (String ignored :
        Arrays.asList(
            "io.argos.agent.core.Foo",
            "io.argos.agent.shaded.net.bytebuddy.ByteBuddy",
            "java.lang.String",
            "java.util.concurrent.LinkedBlockingQueue",
            "javax.management.MBeanServer",
            "jdk.internal.misc.Unsafe",
            "sun.misc.Launcher",
            "com.sun.proxy.$Proxy12",
            "jakarta.annotation.PostConstruct",
            "com.example.Service$$Lambda$12/0x1",
            "com.example.Repo$$SpringCGLIB$$0",
            "com.example.User$HibernateProxy$abc",
            "net.bytebuddy.ByteBuddy",
            "org.jacoco.agent.rt.RT",
            "ch.qos.logback.classic.Logger",
            "org.slf4j.LoggerFactory")) {
      assertThat(IgnoreMatchers.ignored(ignored, none)).as(ignored).isTrue();
    }
    for (String transformable :
        Arrays.asList(
            "com.example.OrderController",
            "javax.servlet.http.HttpServlet",
            "javax.sql.DataSource",
            "jakarta.servlet.http.HttpServlet",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.CompletableFuture$AsyncSupply",
            "sun.net.www.protocol.http.HttpURLConnection",
            "sun.net.www.protocol.https.HttpsURLConnectionImpl",
            "jdk.internal.net.http.HttpClientImpl",
            "ch.qos.logback.classic.spi.LoggingEvent",
            "org.apache.logging.log4j.core.impl.ThreadContextDataInjector$ForDefaultThreadContextMap")) {
      assertThat(IgnoreMatchers.ignored(transformable, none)).as(transformable).isFalse();
    }
    assertThat(IgnoreMatchers.isJdkAllowlisted("java.util.concurrent.ForkJoinPool")).isTrue();
    assertThat(IgnoreMatchers.isJdkAllowlisted("java.util.HashMap")).isFalse();
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("T-AG-042 instrumentation.exclude-classes globs: * within a package, ** across, ?")
  void excludeGlobs() {
    List<Pattern> excludes =
        Arrays.asList(
            Glob.toPattern("com.example.legacy.*"),
            Glob.toPattern("org.vendor.**"),
            Glob.toPattern("com.example.Gen?$Inner"));
    assertThat(IgnoreMatchers.ignored("com.example.legacy.Old", excludes)).isTrue();
    assertThat(IgnoreMatchers.ignored("com.example.legacy.sub.Old", excludes)).isFalse();
    assertThat(IgnoreMatchers.ignored("org.vendor.a.b.C", excludes)).isTrue();
    assertThat(IgnoreMatchers.ignored("com.example.Gen1$Inner", excludes)).isTrue();
    assertThat(IgnoreMatchers.ignored("com.example.Gen12$Inner", excludes)).isFalse();
    assertThat(IgnoreMatchers.ignored("com.example.Other", excludes)).isFalse();
    TypeDescription type = TypeDescription.ForLoadedType.of(Test.class);
    assertThat(IgnoreMatchers.types(Collections.<String>emptyList()).matches(type)).isFalse();
    assertThat(IgnoreMatchers.types(Collections.singletonList("org.junit.**")).matches(type))
        .isTrue();
  }

  @Test
  @DisplayName("C-25 module discovery: service files, id checks, instrumentation.disabled, faults")
  void moduleRegistry() throws IOException {
    File services = new File(dir, ModuleRegistry.SERVICE_FILE);
    Files.createDirectories(services.getParentFile().toPath());
    String prefix = BootTest.class.getName() + "$";
    Files.write(
        services.toPath(),
        Arrays.asList(
            "# comment",
            prefix + "Good",
            prefix + "Second  # trailing comment",
            prefix + "Good",
            prefix + "DuplicateId",
            prefix + "DuplicateModuleId",
            prefix + "InvalidModuleId",
            prefix + "Failing",
            prefix + "Off",
            prefix + "Faulty",
            "com.example.Missing"),
        StandardCharsets.UTF_8);
    Map<String, String> values = new HashMap<String, String>();
    values.put("instrumentation.disabled", "off");
    values.put("test.faults", "advice-throw:faulty,unknown-fault");
    AgentControl control = TestControls.newControl();
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {dir.toURI().toURL()}, BootTest.class.getClassLoader())) {
      List<InstrumentationModule> modules =
          ModuleRegistry.load(loader, AgentConfig.of(values), control);
      List<String> ids = new ArrayList<String>();
      for (InstrumentationModule module : modules) {
        ids.add(module.id());
      }
      assertThat(ids).containsExactly("second", "good", "faulty");
    }
    assertThat(ModuleHealth.state(43)).isEqualTo(ModuleHealth.DISABLED_BY_CONFIG);
    assertThat(ModuleHealth.state(44)).isEqualTo(ModuleHealth.TEST_FAULT);
    assertThat(ModuleHealth.state(40)).isEqualTo(ModuleHealth.ENABLED);
    control.setModuleState(43, ModuleHealth.ENABLED);
    control.setModuleState(44, ModuleHealth.ENABLED);
  }

  @Test
  @DisplayName(
      "agent builder factory: full constructor with NoOp initialization, no class injection")
  void agentBuilderFactory() {
    AgentBuilder builder =
        ArgosAgentBuilder.create(
            new ByteBuddy(),
            new AgentBuilder.RawMatcher.ForElementMatchers(
                ElementMatchers.<TypeDescription>none()));
    assertThat(builder).isInstanceOf(AgentBuilder.Default.class);
    assertThat(builder.disableClassFormatChanges()).isNotNull();
  }

  @Test
  @DisplayName(
      "loader state: bits per module and loader, bootstrap loader included, set atomically")
  void loaderState() throws IOException {
    LoaderState state = new LoaderState();
    try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
      assertThat(state.has(loader, LoaderState.WITNESS_EVALUATED, 5)).isFalse();
      state.add(loader, LoaderState.WITNESS_EVALUATED, 5);
      state.add(loader, LoaderState.WITNESS_MATCHED, 63);
      assertThat(state.has(loader, LoaderState.WITNESS_EVALUATED, 5)).isTrue();
      assertThat(state.has(loader, LoaderState.WITNESS_MATCHED, 63)).isTrue();
      assertThat(state.has(loader, LoaderState.WITNESS_MATCHED, 5)).isFalse();
    }
    state.add(null, LoaderState.HELPERS_INJECTED, 1);
    assertThat(state.has(null, LoaderState.HELPERS_INJECTED, 1)).isTrue();
    assertThat(TransformRecorder.loaderName(null)).isEqualTo("bootstrap");
    assertThat(TransformRecorder.loaderName(ClassLoader.getSystemClassLoader())).isEqualTo("app");
  }

  @Test
  @DisplayName("transformed-class list file: header and one sorted line per class")
  void transformList() throws IOException {
    File list = new File(dir, "out/transformed.txt");
    String[] names = new String[64];
    names[7] = "probe";
    TransformRecorder recorder = new TransformRecorder(list, false, names);
    recorder.onDiscovery("b.B", null, null, false);
    TransformRecorder.markModule(7);
    recorder.onTransformation(
        TypeDescription.ForLoadedType.of(String.class), null, null, true, null);
    recorder.onComplete("b.B", null, null, false);
    recorder.onDiscovery("a.A", null, null, false);
    recorder.onTransformation(
        TypeDescription.ForLoadedType.of(BootTest.class),
        BootTest.class.getClassLoader(),
        null,
        false,
        null);
    recorder.writeIfChanged();
    List<String> lines = Files.readAllLines(list.toPath(), StandardCharsets.UTF_8);
    assertThat(lines)
        .containsExactly(
            TransformRecorder.HEADER,
            "io.argos.agent.core.boot.BootTest - app",
            "java.lang.String probe bootstrap retransformed");
    assertThat(recorder.transformedCount()).isEqualTo(2);
  }
}
