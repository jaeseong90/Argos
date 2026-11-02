/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Module discovery (ADR-0003 C-25, 10 §5.2 step 1): reads every {@value #SERVICE_FILE} visible to
 * the agent class loader (merged into {@code inst/META-INF/services/} by agent-dist), instantiates
 * the listed classes and validates them. The core never references a module class at compile time
 * (B-3).
 *
 * <p>Results: modules sorted by {@link InstrumentationModule#moduleId()}; modules in {@code
 * instrumentation.disabled} are reported {@code DISABLED_BY_CONFIG} and not installed; a module
 * with an invalid or duplicate id is skipped with a WARN. {@code argos.test.faults} ({@link
 * TestFaults}) puts the modules of {@code advice-throw:<module>} into {@link
 * ModuleHealth#TEST_FAULT} (T-AG-040); its other faults act at transformation time. A fault spec
 * that names no installed module (unknown, or off by {@code instrumentation.disabled}) gets one
 * WARN after discovery.
 */
final class ModuleRegistry {
  static final String SERVICE_FILE =
      "META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule";

  private ModuleRegistry() {}

  /** Modules to install (enabled by configuration), in module id order. */
  static List<InstrumentationModule> load(
      ClassLoader agentLoader, AgentConfig config, AgentControl control) {
    return load(agentLoader, config, control, TestFaults.from(config));
  }

  /** {@link #load(ClassLoader, AgentConfig, AgentControl)} with already parsed test faults. */
  static List<InstrumentationModule> load(
      ClassLoader agentLoader, AgentConfig config, AgentControl control, TestFaults faults) {
    Set<String> disabled = new HashSet<String>(config.getList(ConfigKeys.INSTRUMENTATION_DISABLED));
    List<InstrumentationModule> modules = new ArrayList<InstrumentationModule>();
    Set<String> ids = new HashSet<String>();
    List<String> installed = new ArrayList<String>();
    List<String> disabledFound = new ArrayList<String>();
    boolean[] moduleIds = new boolean[ModuleHealth.MAX_MODULES];
    for (String className : classNames(agentLoader)) {
      InstrumentationModule module = instantiate(agentLoader, className);
      if (module == null) {
        continue;
      }
      String id = module.id();
      int moduleId = module.moduleId();
      if (id == null || id.isEmpty() || moduleId < 0 || moduleId > ModuleIds.MAX) {
        AgentLogger.warn(
            "instrumentation module {} skipped: invalid id '{}' or module id {} (0..{})",
            className,
            id,
            moduleId,
            ModuleIds.MAX);
        continue;
      }
      if (!ids.add(id) || moduleIds[moduleId]) {
        AgentLogger.warn(
            "instrumentation module {} skipped: id '{}' or module id {} already taken",
            className,
            id,
            moduleId);
        continue;
      }
      moduleIds[moduleId] = true;
      if (disabled.contains(id)) {
        control.setModuleState(moduleId, ModuleHealth.DISABLED_BY_CONFIG);
        AgentLogger.info("instrumentation module {} disabled by instrumentation.disabled", id);
        disabledFound.add(id);
        continue;
      }
      faults.announce(id);
      if (faults.adviceThrows(id)) {
        control.setModuleState(moduleId, ModuleHealth.TEST_FAULT);
      } else {
        control.setModuleState(moduleId, ModuleHealth.ENABLED);
      }
      modules.add(module);
      installed.add(id);
    }
    // A fault spec that matches no installed module injects nothing: say so (18 §5.7).
    faults.reportUnmatched(installed, disabledFound);
    Collections.sort(
        modules,
        new Comparator<InstrumentationModule>() {
          @Override
          public int compare(InstrumentationModule a, InstrumentationModule b) {
            return Integer.compare(a.moduleId(), b.moduleId());
          }
        });
    return modules;
  }

  /** Class names listed in every service file, in order, without duplicates or comments. */
  static Set<String> classNames(ClassLoader agentLoader) {
    Set<String> names = new LinkedHashSet<String>();
    Enumeration<URL> files;
    try {
      files = agentLoader.getResources(SERVICE_FILE);
    } catch (IOException e) {
      AgentLogger.error("cannot list instrumentation modules ({})", SERVICE_FILE, e);
      return names;
    }
    while (files.hasMoreElements()) {
      URL file = files.nextElement();
      try (InputStream in = file.openStream();
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          int comment = line.indexOf('#');
          String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
          if (!name.isEmpty()) {
            names.add(name);
          }
        }
      } catch (IOException e) {
        AgentLogger.error("cannot read {}", file, e);
      }
    }
    return names;
  }

  private static InstrumentationModule instantiate(ClassLoader agentLoader, String className) {
    try {
      Class<?> type = Class.forName(className, true, agentLoader);
      return type.asSubclass(InstrumentationModule.class).getConstructor().newInstance();
    } catch (Throwable t) {
      AgentLogger.warn(
          "instrumentation module {} could not be created: {}", className, t.toString());
      return null;
    }
  }
}
