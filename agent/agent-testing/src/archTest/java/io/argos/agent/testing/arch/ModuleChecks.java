/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Consistency of instrumentation modules (10 §5.1-§5.4, ADR-0003 C-25), checked on the class files
 * with ASM because helpers and advice reference library types (servlet API, JDBC drivers) that are
 * not on this classpath:
 *
 * <ul>
 *   <li>the module is listed in a module index file and its {@code id()} is the Gradle module name
 *       (the {@code instrumentation.disabled} value); {@code moduleId()} is a production id;
 *   <li>helpers ({@link HelperClasses#problems}): in the module package, named {@code *Helper*},
 *       class file present, no static state (static fields only compile-time constants), super
 *       class {@code Object} or a helper listed before it (dependency order);
 *   <li>advice applied by {@code transform()}: class file present, in the module package, named
 *       {@code *Advice}, with {@code @Advice.OnMethodEnter} or {@code @Advice.OnMethodExit}
 *       methods.
 * </ul>
 */
final class ModuleChecks {
  static final String SERVICE_FILE =
      "META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule";

  private static final String ADVICE_ENTER = "Lnet/bytebuddy/asm/Advice$OnMethodEnter;";
  private static final String ADVICE_EXIT = "Lnet/bytebuddy/asm/Advice$OnMethodExit;";

  private ModuleChecks() {}

  /** Module class names of every module index file visible to {@code loader}. */
  static Set<String> indexedModules(ClassLoader loader) {
    Set<String> names = new LinkedHashSet<>();
    try {
      for (URL file : Collections.list(loader.getResources(SERVICE_FILE))) {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(file.openStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            String name = line.replaceFirst("#.*", "").trim();
            if (!name.isEmpty()) {
              names.add(name);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  /**
   * Problems of {@code module}; empty when consistent. {@code gradleModules} are the
   * instrumentation module names of settings.gradle.kts.
   */
  static List<String> problems(
      InstrumentationModule module, ClassLoader loader, List<String> gradleModules) {
    List<String> problems = new ArrayList<>();
    String moduleClass = module.getClass().getName();
    String packagePrefix = moduleClass.substring(0, moduleClass.lastIndexOf('.') + 1);
    if (!indexedModules(loader).contains(moduleClass)) {
      problems.add(moduleClass + " is not listed in a " + SERVICE_FILE + " file (C-25)");
    }
    if (!gradleModules.contains(module.id())) {
      problems.add(
          moduleClass
              + ".id() '"
              + module.id()
              + "' is not an instrumentation module name of settings.gradle.kts "
              + gradleModules);
    }
    if (module.moduleId() < 0 || module.moduleId() >= ModuleIds.TEST_FIRST) {
      problems.add(
          moduleClass
              + ".moduleId() "
              + module.moduleId()
              + " is outside the production range 0.."
              + (ModuleIds.TEST_FIRST - 1));
    }
    if (module.types().isEmpty()) {
      problems.add(moduleClass + " has no type instrumentation");
    }
    problems.addAll(HelperClasses.problems(module));
    Set<String> listedHelpers = new LinkedHashSet<>();
    for (String helper : module.helperClassNames()) {
      ClassNode node = classFile(loader, helper);
      if (node == null) {
        problems.add("helper " + helper + " has no class file");
        listedHelpers.add(helper);
        continue;
      }
      for (FieldNode field : node.fields) {
        boolean constant =
            (field.access & Opcodes.ACC_STATIC) != 0
                && (field.access & Opcodes.ACC_FINAL) != 0
                && (Type.getType(field.desc).getSort() != Type.OBJECT
                    || field.desc.equals("Ljava/lang/String;"));
        if ((field.access & Opcodes.ACC_STATIC) != 0 && !constant) {
          problems.add(
              "helper "
                  + helper
                  + " has static state "
                  + field.name
                  + " (10 §5.4: a copy per class loader, no static fields)");
        }
      }
      String superName = node.superName == null ? null : node.superName.replace('/', '.');
      if (superName != null
          && !superName.equals("java.lang.Object")
          && superName.startsWith(packagePrefix)
          && !listedHelpers.contains(superName)) {
        problems.add(
            "helper "
                + helper
                + " extends "
                + superName
                + ", which is not a helper listed before it (dependency order)");
      }
      listedHelpers.add(helper);
    }
    for (TypeInstrumentation instrumentation : module.types()) {
      for (String advice : adviceOf(instrumentation)) {
        problems.addAll(adviceProblems(loader, packagePrefix, advice));
      }
    }
    return problems;
  }

  /** The advice class names a type instrumentation applies. */
  static List<String> adviceOf(TypeInstrumentation instrumentation) {
    final List<String> advice = new ArrayList<>();
    instrumentation.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methods, String adviceClassName) {
            advice.add(adviceClassName);
          }
        });
    return advice;
  }

  private static List<String> adviceProblems(
      ClassLoader loader, String packagePrefix, String advice) {
    List<String> problems = new ArrayList<>();
    if (!advice.startsWith(packagePrefix) || advice.indexOf('.', packagePrefix.length()) >= 0) {
      problems.add("advice " + advice + " is not in the module package " + packagePrefix);
    }
    if (!advice.endsWith("Advice")) {
      problems.add("advice " + advice + " does not end with 'Advice' (10 §5.3 A-1)");
    }
    ClassNode node = classFile(loader, advice);
    if (node == null) {
      problems.add("advice " + advice + " has no class file");
      return problems;
    }
    boolean annotated = false;
    for (MethodNode method : node.methods) {
      if (method.visibleAnnotations == null) {
        continue;
      }
      for (AnnotationNode annotation : method.visibleAnnotations) {
        if (annotation.desc.equals(ADVICE_ENTER) || annotation.desc.equals(ADVICE_EXIT)) {
          annotated = true;
        }
      }
    }
    if (!annotated) {
      problems.add(
          "advice " + advice + " has no @Advice.OnMethodEnter/@Advice.OnMethodExit method");
    }
    return problems;
  }

  /** The class file of {@code className} through {@code loader}, or null. */
  static ClassNode classFile(ClassLoader loader, String className) {
    try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
      if (in == null) {
        return null;
      }
      ClassNode node = new ClassNode();
      new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
      return node;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
