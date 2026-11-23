/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The module on JDK 8 (task {@code testJdk8}; this class refers to no java.net.http type). The
 * module is compiled with {@code -source 8 -target 8} instead of {@code --release 8} (ADR-0003,
 * WO-14 assumption (a)), so these checks replace what {@code --release 8} would enforce:
 *
 * <ul>
 *   <li>the witness fails on JDK 8, so the module stays disabled there;
 *   <li>every class file is major 52, and every JDK class, method and field it refers to exists on
 *       JDK 8, except java.net.http and jdk.internal.net.http, which only advice and helper classes
 *       may refer to (they run on JDK 11+ only; the module class is loaded on every JDK).
 * </ul>
 */
class Jdk11ModuleOnJdk8Test {
  private static final String[] NEWER_PACKAGES = {"java/net/http/", "jdk/internal/net/http/"};
  private static final String[] JDK_PACKAGES = {"java/", "javax/", "jdk/", "sun/", "com/sun/"};

  @Test
  @DisplayName("on JDK 8 the witness fails for every loader: the module stays disabled")
  void witnessFailsOnJdk8() {
    assertThat(System.getProperty("java.specification.version")).isEqualTo("1.8");
    HttpClientJdk11Module module = new HttpClientJdk11Module();
    ClassLoader system = ClassLoader.getSystemClassLoader();
    assertThat(module.witness().matches(null)).isFalse();
    assertThat(module.witness().matches(system)).isFalse();
    assertThat(module.witness().matches(system.getParent())).isFalse();
    assertThat(module.types()).hasSize(2);
    assertThat(module.helperClassNames()).hasSize(1);
  }

  @Test
  @DisplayName(
      "ADR-0003 (a): class files major 52; outside the JDK 8 API only java.net.http /"
          + " jdk.internal.net.http, from advice and helper classes only")
  void referencesOutsideJdk8AreOnlyJavaNetHttp() throws Exception {
    Path classes =
        Paths.get(
            HttpClientJdk11Module.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    List<Path> files;
    try (Stream<Path> walk = Files.walk(classes)) {
      files = walk.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
    }
    assertThat(files).hasSizeGreaterThanOrEqualTo(4);
    List<String> problems = new ArrayList<String>();
    Set<String> newerUsers = new TreeSet<String>();
    for (Path file : files) {
      byte[] bytes = Files.readAllBytes(file);
      References refs = References.of(bytes);
      String name = refs.className.substring(refs.className.lastIndexOf('/') + 1);
      if (refs.major != 52) {
        problems.add(refs.className + ": class file major " + refs.major);
      }
      boolean mayUseNewer = name.endsWith("Advice") || name.contains("Helper");
      for (String type : refs.types) {
        if (!startsWithAny(type, JDK_PACKAGES)) {
          continue;
        }
        if (startsWithAny(type, NEWER_PACKAGES)) {
          newerUsers.add(name);
          if (!mayUseNewer) {
            problems.add(refs.className + " (loaded on JDK 8) refers to " + type);
          }
          continue;
        }
        if (load(type) == null) {
          problems.add(refs.className + " refers to " + type + ", missing on JDK 8");
        }
      }
      for (String[] call : refs.methods) {
        if (call[0].startsWith("[")
            || !startsWithAny(call[0], JDK_PACKAGES)
            || startsWithAny(call[0], NEWER_PACKAGES)) {
          continue;
        }
        Class<?> owner = load(call[0]);
        if (owner != null && !hasMethod(owner, call[1], call[2])) {
          problems.add(
              refs.className
                  + " calls "
                  + call[0]
                  + "."
                  + call[1]
                  + call[2]
                  + ", missing on JDK 8");
        }
      }
      for (String[] field : refs.fields) {
        if (!startsWithAny(field[0], JDK_PACKAGES) || startsWithAny(field[0], NEWER_PACKAGES)) {
          continue;
        }
        Class<?> owner = load(field[0]);
        if (owner != null && !hasField(owner, field[1], field[2])) {
          problems.add(
              refs.className + " reads " + field[0] + "." + field[1] + ", missing on JDK 8");
        }
      }
    }
    assertThat(problems).isEmpty();
    assertThat(newerUsers)
        .as("the check is not vacuous: these classes use java.net.http")
        .contains("Jdk11RequestHelper", "HttpClientSendAdvice", "MultiExchangeAdvice")
        .doesNotContain("HttpClientJdk11Module");
  }

  /** The class and member references of one class file. */
  private static final class References {
    String className;
    int major;
    final Set<String> types = new TreeSet<String>();
    final List<String[]> methods = new ArrayList<String[]>();
    final List<String[]> fields = new ArrayList<String[]>();

    static References of(byte[] bytes) {
      final References refs = new References();
      refs.major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
      ClassVisitor collector =
          new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces) {
              refs.className = name;
            }

            @Override
            public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
              return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                    int opcode, String owner, String method, String desc, boolean isInterface) {
                  refs.methods.add(new String[] {owner, method, desc});
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                  refs.fields.add(new String[] {owner, field, desc});
                }
              };
            }
          };
      Remapper recorder =
          new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
              refs.types.add(internalName);
              return internalName;
            }
          };
      new ClassReader(bytes).accept(new ClassRemapper(collector, recorder), 0);
      return refs;
    }
  }

  private static boolean startsWithAny(String name, String[] prefixes) {
    for (String prefix : prefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static Class<?> load(String internalName) {
    try {
      return Class.forName(
          internalName.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  /** {@code type}, its superclasses and all their interfaces. */
  private static List<Class<?>> hierarchy(Class<?> type) {
    List<Class<?>> all = new ArrayList<Class<?>>();
    Set<Class<?>> seen = new HashSet<Class<?>>();
    Deque<Class<?>> todo = new ArrayDeque<Class<?>>();
    todo.add(type);
    while (!todo.isEmpty()) {
      Class<?> c = todo.poll();
      if (!seen.add(c)) {
        continue;
      }
      all.add(c);
      if (c.getSuperclass() != null) {
        todo.add(c.getSuperclass());
      }
      for (Class<?> i : c.getInterfaces()) {
        todo.add(i);
      }
    }
    if (type.isInterface()) {
      all.add(Object.class);
    }
    return all;
  }

  private static boolean hasMethod(Class<?> owner, String name, String descriptor) {
    if (name.equals("<init>")) {
      for (Constructor<?> c : owner.getDeclaredConstructors()) {
        if (Type.getConstructorDescriptor(c).equals(descriptor)) {
          return true;
        }
      }
      return false;
    }
    for (Class<?> c : hierarchy(owner)) {
      for (Method m : c.getDeclaredMethods()) {
        if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(descriptor)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasField(Class<?> owner, String name, String descriptor) {
    for (Class<?> c : hierarchy(owner)) {
      for (Field f : c.getDeclaredFields()) {
        if (f.getName().equals(name) && Type.getDescriptor(f.getType()).equals(descriptor)) {
          return true;
        }
      }
    }
    return false;
  }
}
