/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Bytecode-level advice checks that ArchUnit's model cannot express (19 §13 #8, U-8): ArchUnit has
 * no notion of invokedynamic, monitorenter or instruction order, so these conditions read the class
 * file of each advice class with ASM.
 */
final class AdviceBytecode {
  /** Opcode names for messages (ASM's Printer lives in asm-util, which this suite does not use). */
  private static final Map<Integer, String> OPCODE_NAMES = opcodeNames();

  private AdviceBytecode() {}

  /** The class file of {@code javaClass}, parsed with line numbers. */
  static ClassNode read(JavaClass javaClass) {
    Source source =
        javaClass
            .getSource()
            .orElseThrow(() -> new IllegalStateException("No class file for " + javaClass));
    try (InputStream in = source.getUri().toURL().openStream()) {
      ClassNode node = new ClassNode();
      new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
      return node;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + source.getUri(), e);
    }
  }

  /** True for methods annotated with {@code @Advice.OnMethodEnter/OnMethodExit} (any form). */
  static boolean isAdviceMethod(AgentLayout layout, MethodNode method) {
    return hasAdviceAnnotation(layout, method.visibleAnnotations)
        || hasAdviceAnnotation(layout, method.invisibleAnnotations);
  }

  private static boolean hasAdviceAnnotation(AgentLayout layout, List<AnnotationNode> annotations) {
    if (annotations == null) {
      return false;
    }
    return annotations.stream()
        .map(a -> layout.adviceMemberName(Type.getType(a.desc).getClassName()))
        .anyMatch(
            name ->
                name.equals(Optional.of(AgentPredicates.ON_METHOD_ENTER))
                    || name.equals(Optional.of(AgentPredicates.ON_METHOD_EXIT)));
  }

  private static String where(JavaClass javaClass, MethodNode method, int line) {
    return javaClass.getName()
        + "."
        + method.name
        + method.desc
        + (line > 0 ? " (" + javaClass.getSimpleName() + ".java:" + line + ")" : "");
  }

  /**
   * T-AG-001a: no invokedynamic anywhere in the class (lambdas, method references, indy concat).
   */
  static ArchCondition<JavaClass> containNoInvokeDynamic() {
    return new ArchCondition<>("contain no lambda, method reference or other invokedynamic") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (MethodNode method : read(item).methods) {
          int line = -1;
          for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode lineNumber) {
              line = lineNumber.line;
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
              String kind =
                  indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                      ? "lambda or method reference"
                      : "invokedynamic (" + indy.bsm.getOwner() + "." + indy.bsm.getName() + ")";
              events.add(
                  SimpleConditionEvent.violated(
                      item, where(item, method, line) + " contains a " + kind));
            }
          }
        }
      }
    };
  }

  /** T-AG-003b: no synchronized method and no synchronized block (monitorenter). */
  static ArchCondition<JavaClass> notSynchronize() {
    return new ArchCondition<>("not synchronize (no synchronized method, no monitorenter)") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (MethodNode method : read(item).methods) {
          if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            events.add(
                SimpleConditionEvent.violated(
                    item, where(item, method, -1) + " is a synchronized method"));
          }
          int line = -1;
          for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode lineNumber) {
              line = lineNumber.line;
            } else if (insn.getOpcode() == Opcodes.MONITORENTER) {
              events.add(
                  SimpleConditionEvent.violated(
                      item, where(item, method, line) + " enters a monitor (synchronized block)"));
            }
          }
        }
      }
    };
  }

  /**
   * T-AG-003a (10 §5.3 A-2, ADV-03): every advice method, enter and exit, starts with {@code if
   * (Argos.skip(MODULE_ID)) return;}. In bytecode (labels, line numbers and frames skipped):
   *
   * <ol>
   *   <li>one instruction that pushes the module ID (an int constant or static int field), then
   *       {@code INVOKESTATIC <guard holder>.skip(I)Z} as the first call of the method;
   *   <li>directly after it {@code IFEQ} or {@code IFNE} on the result;
   *   <li>on the path taken when skip returns true, a return as the first instruction: {@code
   *       RETURN}, or a default value ({@code ACONST_NULL}, {@code ICONST_0}, {@code LCONST_0},
   *       {@code FCONST_0}, {@code DCONST_0}) followed by the matching {@code xRETURN}.
   * </ol>
   *
   * <p>{@code Argos.skip} checks the kill switch, the module state and the agent thread (10 §3), so
   * it is the "equivalent guard" of 19 §5.3 ADV-03. No other guard and no unguarded helper call is
   * accepted.
   */
  static ArchCondition<JavaClass> callTheGuardFirst(AgentLayout layout) {
    String guardOwner = layout.guardHolder().replace('.', '/');
    String expected =
        "expected the first statement if (" + layout.guardHolder() + ".skip(MODULE_ID)) return;";
    return new ArchCondition<>(
        "start every advice method with if ("
            + layout.guardHolder()
            + ".skip(MODULE_ID)) return;") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (MethodNode method : read(item).methods) {
          if (!isAdviceMethod(layout, method)) {
            continue;
          }
          String problem = guardProblem(method, guardOwner);
          if (problem != null) {
            events.add(
                SimpleConditionEvent.violated(
                    item,
                    where(item, method, firstLine(method)) + " " + problem + "; " + expected));
          }
        }
      }
    };
  }

  /** Why {@code method} does not start with the guard, or null when it does. */
  private static String guardProblem(MethodNode method, String guardOwner) {
    List<AbstractInsnNode> code = new ArrayList<>();
    for (AbstractInsnNode insn : method.instructions) {
      if (insn.getOpcode() >= 0) {
        code.add(insn);
      }
    }
    int first = -1;
    for (int i = 0; i < code.size(); i++) {
      if (code.get(i) instanceof MethodInsnNode || code.get(i) instanceof InvokeDynamicInsnNode) {
        first = i;
        break;
      }
    }
    if (first < 0) {
      return "never calls " + guardOwner.replace('/', '.') + ".skip(int)";
    }
    AbstractInsnNode call = code.get(first);
    boolean guard =
        call instanceof MethodInsnNode m
            && m.getOpcode() == Opcodes.INVOKESTATIC
            && m.owner.equals(guardOwner)
            && m.name.equals("skip")
            && m.desc.equals("(I)Z");
    if (!guard) {
      return "calls " + describe(call) + " first";
    }
    if (first > 1) {
      return "runs other code before the guard ("
          + (first - 1)
          + " instructions, first "
          + describe(code.get(0))
          + ")";
    }
    if (first != 1 || !pushesModuleId(code.get(0))) {
      return "computes the module ID of skip(int) ("
          + (first == 1 ? describe(code.get(0)) : "nothing")
          + "), expected an int constant or static int field";
    }
    if (first + 1 >= code.size()) {
      return "does not branch on the result of skip(int)";
    }
    AbstractInsnNode branch = code.get(first + 1);
    if (branch.getOpcode() != Opcodes.IFEQ && branch.getOpcode() != Opcodes.IFNE) {
      return "does not branch on the result of skip(int) (next instruction: "
          + describe(branch)
          + ")";
    }
    // IFEQ jumps when skip returned false: the skip path falls through. IFNE jumps on true.
    AbstractInsnNode skipPath =
        branch.getOpcode() == Opcodes.IFEQ
            ? nextReal(branch.getNext())
            : nextReal(((JumpInsnNode) branch).label);
    if (!returnsAtOnce(skipPath)) {
      return "does not return at once when skip(int) is true (found "
          + (skipPath == null ? "no instruction" : describe(skipPath))
          + ")";
    }
    return null;
  }

  /** An int constant or a static int field: the module ID argument of skip(int). */
  private static boolean pushesModuleId(AbstractInsnNode insn) {
    int opcode = insn.getOpcode();
    return (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
        || opcode == Opcodes.BIPUSH
        || opcode == Opcodes.SIPUSH
        || (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer)
        || (insn instanceof FieldInsnNode field
            && opcode == Opcodes.GETSTATIC
            && field.desc.equals("I"));
  }

  /** RETURN, or a default value followed by the matching return instruction. */
  private static boolean returnsAtOnce(AbstractInsnNode insn) {
    if (insn == null) {
      return false;
    }
    if (insn.getOpcode() == Opcodes.RETURN) {
      return true;
    }
    AbstractInsnNode after = nextReal(insn.getNext());
    if (after == null) {
      return false;
    }
    int expectedReturn =
        switch (insn.getOpcode()) {
          case Opcodes.ACONST_NULL -> Opcodes.ARETURN;
          case Opcodes.ICONST_0 -> Opcodes.IRETURN;
          case Opcodes.LCONST_0 -> Opcodes.LRETURN;
          case Opcodes.FCONST_0 -> Opcodes.FRETURN;
          case Opcodes.DCONST_0 -> Opcodes.DRETURN;
          default -> -1;
        };
    return expectedReturn >= 0 && after.getOpcode() == expectedReturn;
  }

  /** {@code insn} or the first instruction after it that is not a label, line number or frame. */
  private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
    AbstractInsnNode current = insn;
    while (current != null && current.getOpcode() < 0) {
      current = current.getNext();
    }
    return current;
  }

  private static int firstLine(MethodNode method) {
    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof LineNumberNode lineNumber) {
        return lineNumber.line;
      }
    }
    return -1;
  }

  private static String describe(AbstractInsnNode insn) {
    if (insn instanceof MethodInsnNode m) {
      return m.owner.replace('/', '.') + "." + m.name + m.desc;
    }
    if (insn instanceof InvokeDynamicInsnNode) {
      return "invokedynamic";
    }
    return OPCODE_NAMES.getOrDefault(insn.getOpcode(), "opcode " + insn.getOpcode());
  }

  /** Instruction opcodes of {@link Opcodes} by value (other int constants use known prefixes). */
  private static Map<Integer, String> opcodeNames() {
    Map<Integer, String> names = new HashMap<>();
    for (Field field : Opcodes.class.getFields()) {
      String name = field.getName();
      boolean otherConstant = name.matches("(ACC|T|H|F|V|ASM|SOURCE)_.*|V\\d.*|ASM\\d.*|V_PREVIEW");
      if (field.getType() == int.class
          && Modifier.isStatic(field.getModifiers())
          && !otherConstant) {
        try {
          int value = field.getInt(null);
          if (value >= 0 && value <= 255) {
            names.putIfAbsent(value, name);
          }
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return Map.copyOf(names);
  }
}
