/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * ST-91-1 (16 §1.4 "보증 논리" 1, DSN-16-05, SEC-91): the code that handles server commands and remote
 * configuration has no path that turns data into code. In the agent's command and configuration
 * packages ({@code <core>.command}, {@code <core>.config} and below):
 *
 * <ul>
 *   <li>never: {@code javax.script.*}, {@code ProcessBuilder}, {@code Runtime.exec}, {@code
 *       Runtime/System.load|loadLibrary}, {@code ClassLoader.loadClass|defineClass|findClass} (any
 *       class loader), a new {@code URLClassLoader}, {@code MethodHandles.Lookup.find*|unreflect*},
 *       {@code Class.getMethods|getDeclaredMethods|getFields|getDeclaredFields|getConstructors|
 *       getDeclaredConstructors|newInstance}, {@code ServiceLoader}, {@code
 *       Instrumentation.redefineClasses|appendToBootstrapClassLoaderSearch|
 *       appendToSystemClassLoaderSearch} (the custom-method path only re-applies the precompiled
 *       advice with {@code retransformClasses});
 *   <li>only with a compile-time constant name: {@code Class.forName} and {@code
 *       Class.getMethod|getDeclaredMethod|getField|getDeclaredField} (for example the JDK 21 {@code
 *       HotSpotDiagnosticMXBean.dumpThreads} lookup of THREAD_DUMP). A {@code Method} can therefore
 *       only name a member written in the agent's source, and {@code Method.invoke} on it is
 *       allowed.
 * </ul>
 *
 * <p>The constant check walks the bytecode backwards with {@link AgentCodeRules.Flow}: every origin
 * of the name argument must be an {@code LDC} of a string.
 */
final class RemoteControlRules {

  /** Owner prefixes whose every method call is forbidden (internal names). */
  static final Set<String> FORBIDDEN_OWNER_PREFIXES =
      Set.of("javax/script/", "java/lang/ProcessBuilder", "java/util/ServiceLoader");

  /** {@code owner#name} calls that are forbidden whatever their arguments. */
  static final Set<String> FORBIDDEN_CALLS =
      Set.of(
          "java/lang/Runtime#exec",
          "java/lang/Runtime#load",
          "java/lang/Runtime#loadLibrary",
          "java/lang/System#load",
          "java/lang/System#loadLibrary",
          "java/lang/Class#getMethods",
          "java/lang/Class#getDeclaredMethods",
          "java/lang/Class#getFields",
          "java/lang/Class#getDeclaredFields",
          "java/lang/Class#getConstructors",
          "java/lang/Class#getDeclaredConstructors",
          "java/lang/Class#newInstance",
          "java/net/URLClassLoader#<init>",
          "java/net/URLClassLoader#newInstance",
          "java/lang/instrument/Instrumentation#redefineClasses",
          "java/lang/instrument/Instrumentation#appendToBootstrapClassLoaderSearch",
          "java/lang/instrument/Instrumentation#appendToSystemClassLoaderSearch");

  /** Methods of any {@code *ClassLoader} owner that load or define classes. */
  static final Set<String> CLASS_LOADER_METHODS = Set.of("loadClass", "defineClass", "findClass");

  /** {@code owner#name} calls allowed only when their name argument is a string constant. */
  static final Set<String> CONSTANT_NAME_CALLS =
      Set.of(
          "java/lang/Class#forName",
          "java/lang/Class#getMethod",
          "java/lang/Class#getDeclaredMethod",
          "java/lang/Class#getField",
          "java/lang/Class#getDeclaredField");

  private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";

  private RemoteControlRules() {}

  static List<ArchRule> all(AgentLayout layout) {
    return List.of(noDynamicCodeInRemoteControl(layout));
  }

  /** The packages that handle server commands and remote configuration. */
  static List<String> remoteControlPackages(AgentLayout layout) {
    return List.of(layout.core() + ".command", layout.core() + ".config");
  }

  /** ST-91-1 [16 §1.4, SEC-91]. */
  static ArchRule noDynamicCodeInRemoteControl(AgentLayout layout) {
    List<String> packages = remoteControlPackages(layout);
    DescribedPredicate<JavaClass> remoteControl =
        DescribedPredicate.describe(
            "classes of " + String.join(" and ", packages),
            c -> packages.stream().anyMatch(p -> AgentLayout.inPackage(c.getName(), p)));
    return classes()
        .that(remoteControl)
        .should(
            new ArchCondition<JavaClass>(
                "not load, define, look up or run code named by data (class loading, method"
                    + " handles, scripts, processes, member enumeration; Class.forName and"
                    + " getMethod only with constant names)") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (String violation : violations(item)) {
                  events.add(SimpleConditionEvent.violated(item, violation));
                }
              }
            })
        .as(
            "SEC-91 [ST-91-1] agent command and remote-config code has no dynamic class loading,"
                + " lookup or execution API")
        .because(
            "a server message must never become code in the application JVM: the command list is"
                + " closed and remote configuration only sets enumerated keys (16 §1.4)");
  }

  static List<String> violations(JavaClass item) {
    List<String> violations = new ArrayList<>();
    ClassNode node = AdviceBytecode.read(item);
    for (MethodNode method : node.methods) {
      AgentCodeRules.Flow flow = null;
      int line = -1;
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof LineNumberNode lineNumber) {
          line = lineNumber.line;
          continue;
        }
        if (insn instanceof TypeInsnNode type
            && type.getOpcode() == Opcodes.NEW
            && forbiddenOwner(type.desc)) {
          violations.add(
              AgentCodeRules.where(item, method, line)
                  + ": creates "
                  + type.desc.replace('/', '.'));
          continue;
        }
        if (!(insn instanceof MethodInsnNode call)) {
          continue;
        }
        String at = AgentCodeRules.where(item, method, line);
        String key = call.owner + "#" + call.name;
        String shown = call.owner.replace('/', '.') + "." + call.name;
        if (forbiddenOwner(call.owner) || FORBIDDEN_CALLS.contains(key)) {
          violations.add(at + ": calls " + shown);
        } else if (call.owner.endsWith("ClassLoader") && CLASS_LOADER_METHODS.contains(call.name)) {
          violations.add(at + ": calls " + shown + " (class loading)");
        } else if (call.owner.equals(LOOKUP)
            && (call.name.startsWith("find") || call.name.startsWith("unreflect"))) {
          violations.add(at + ": calls " + shown + " (method handle lookup)");
        } else if (CONSTANT_NAME_CALLS.contains(key)) {
          int depth = nameArgumentDepth(call.desc);
          if (depth < 0) {
            violations.add(at + ": calls " + shown + " without a name argument");
            continue;
          }
          if (flow == null) {
            flow = new AgentCodeRules.Flow(method);
          }
          for (AbstractInsnNode origin : flow.valueOf(call, depth)) {
            if (!(origin instanceof LdcInsnNode ldc && ldc.cst instanceof String)) {
              violations.add(
                  at
                      + ": "
                      + shown
                      + " name is not a constant (comes from "
                      + AgentCodeRules.describe(origin)
                      + ")");
            }
          }
        }
      }
    }
    return violations;
  }

  private static boolean forbiddenOwner(String internalName) {
    return FORBIDDEN_OWNER_PREFIXES.stream().anyMatch(internalName::startsWith);
  }

  /**
   * Stack depth (0 = top) of the first {@code String} parameter of a call just before it: the class
   * or member name of {@code Class.forName(String[, boolean, ClassLoader])}, {@code forName(Module,
   * String)} and {@code getMethod(String, Class...)}; -1 when there is none.
   */
  static int nameArgumentDepth(String descriptor) {
    Type[] arguments = Type.getArgumentTypes(descriptor);
    for (int i = 0; i < arguments.length; i++) {
      if (arguments[i].getDescriptor().equals("Ljava/lang/String;")) {
        return arguments.length - 1 - i;
      }
    }
    return -1;
  }
}
