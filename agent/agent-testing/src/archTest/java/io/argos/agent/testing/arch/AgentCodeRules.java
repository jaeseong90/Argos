/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Rules on the agent's own code that need to know where a value comes from inside a method, which
 * ArchUnit's model does not have. {@link Flow} finds the instructions that produce a stack value by
 * walking the bytecode backwards (through copies, locals and branch merges), with ASM core and tree
 * only:
 *
 * <ul>
 *   <li>LOG-01 (10 §14 "정적 검사", SEC-26): the arguments of {@code AgentLogger.info/warn/error/
 *       warnOnce}, the levels an agent at {@code log.level=INFO} writes, are constants, numbers,
 *       booleans, enums, classes, files and paths, throwables, or strings from an allow-listed
 *       identifier source ({@link LogAllowlist}); the format is a constant. Collected data (SQL,
 *       parameters, headers, URLs, user ids) cannot reach those levels: a string of unknown origin
 *       (a parameter, a field, a collection element) fails unless its call site is allow-listed
 *       with a reason. DEBUG is not checked (documented as possibly containing personal data).
 *   <li>A-7 (10 §5.3): advice applied to constructors has no {@code @Advice.OnMethodEnter} (a
 *       constructor gets exit advice only): every {@code TypeTransformer.applyAdvice} call whose
 *       method matcher comes from {@code ElementMatchers.isConstructor()} (or {@code
 *       named("<init>")}, also through a method of the same class) names, as a class literal, an
 *       advice class without an enter method.
 *   <li>A-9 (10 §5.3, §8 "보관 타입"): the value type of every {@code ContextStore.of(key, value)} in
 *       instrumentation code is a bootstrap type, a boxed primitive, {@code String}, {@code long[]}
 *       or {@code WeakReference}, given as a class literal, so a store never keeps an application
 *       object alive.
 * </ul>
 */
final class AgentCodeRules {
  static final Set<String> LOG_METHODS = Set.of("info", "warn", "error", "warnOnce");

  static final Set<String> BOXED =
      Set.of(
          "java/lang/Integer",
          "java/lang/Long",
          "java/lang/Short",
          "java/lang/Byte",
          "java/lang/Double",
          "java/lang/Float",
          "java/lang/Boolean",
          "java/lang/Character");

  /** Reference types that never carry collected data. */
  static final Set<String> SAFE_TYPES =
      Set.of(
          "java/lang/Class",
          "java/io/File",
          "java/nio/file/Path",
          "java/lang/Thread$State",
          "java/lang/Number");

  /** JDK methods returning identifiers or numbers as strings. */
  static final Set<String> JDK_PRODUCERS =
      Set.of(
          "java/lang/Class#getName",
          "java/lang/Class#getSimpleName",
          "java/lang/Integer#toString",
          "java/lang/Long#toString",
          "java/lang/Integer#toHexString",
          "java/lang/Long#toHexString",
          "java/lang/System#getProperty",
          "java/lang/Thread#getName",
          "java/lang/Enum#name",
          "java/io/File#getPath",
          "java/io/File#getAbsolutePath",
          "java/io/File#getName");

  /** Upper bound of the backward search (copies, locals, merges). */
  private static final int MAX_DEPTH = 24;

  private AgentCodeRules() {}

  static List<ArchRule> all(AgentLayout layout, LogAllowlist allowlist) {
    return List.of(
        logArgumentsAreSafe(layout, allowlist),
        constructorsGetExitAdviceOnly(layout),
        contextStoreValuesAreSafe(layout));
  }

  // --- LOG-01 ----------------------------------------------------------------------------------

  /** LOG-01 [10 §14, SEC-26]. */
  static ArchRule logArgumentsAreSafe(AgentLayout layout, LogAllowlist allowlist) {
    String logger = layout.core().replace('.', '/') + "/log/AgentLogger";
    String logPackage = layout.core() + ".log";
    DescribedPredicate<JavaClass> callers =
        DescribedPredicate.describe(
            "agent classes outside the logger package",
            c ->
                layout.isAgentClass(c.getName())
                    && !AgentLayout.inPackage(c.getName(), logPackage)
                    && !layout.isShadedLibrary(c.getName()));
    return classes()
        .that(callers)
        .should(
            new ArchCondition<JavaClass>(
                "pass only constants, numbers, enums and allow-listed identifiers to INFO, WARN"
                    + " and ERROR log calls") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (String violation : logViolations(item, logger, allowlist)) {
                  events.add(SimpleConditionEvent.violated(item, violation));
                }
              }
            })
        .as(
            "LOG-01 [10 §14, SEC-26] INFO/WARN/ERROR log arguments are constants, numbers, enums or"
                + " allow-listed identifiers")
        .because(
            "collected data (SQL, parameters, headers, URLs, user ids) must not reach the logs an"
                + " agent writes at log.level=INFO (SEC-26)");
  }

  static List<String> logViolations(JavaClass item, String logger, LogAllowlist allowlist) {
    List<String> violations = new ArrayList<>();
    ClassNode node = AdviceBytecode.read(item);
    for (MethodNode method : node.methods) {
      if (!callsLogger(method, logger) || allowlist.allowsSite(item.getName(), method.name)) {
        continue;
      }
      Flow flow = new Flow(method);
      int line = -1;
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof LineNumberNode lineNumber) {
          line = lineNumber.line;
          continue;
        }
        if (!(insn instanceof MethodInsnNode call) || !isLogCall(call, logger)) {
          continue;
        }
        String at = where(item, method, line) + ": AgentLogger." + call.name;
        for (AbstractInsnNode origin : flow.valueOf(call, 1)) {
          if (!(origin instanceof LdcInsnNode ldc && ldc.cst instanceof String)) {
            violations.add(at + " format is not a constant (" + describe(origin) + ")");
          }
        }
        Set<AbstractInsnNode> arrays = flow.valueOf(call, 0);
        for (AbstractInsnNode origin : arrays) {
          if (origin.getOpcode() != Opcodes.ANEWARRAY) {
            violations.add(at + " arguments are not a varargs array (" + describe(origin) + ")");
          }
        }
        for (AbstractInsnNode store : flow.storesInto(arrays, call)) {
          for (AbstractInsnNode origin : flow.valueOf(store, 0)) {
            flow.unsafe(origin, allowlist, 0)
                .ifPresent(problem -> violations.add(at + " argument comes from " + problem));
          }
        }
      }
    }
    return violations;
  }

  static boolean isLogCall(MethodInsnNode call, String logger) {
    return call.owner.equals(logger)
        && LOG_METHODS.contains(call.name)
        && call.desc.endsWith("[Ljava/lang/Object;)V");
  }

  private static boolean callsLogger(MethodNode method, String logger) {
    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof MethodInsnNode call && isLogCall(call, logger)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Where values come from in one method: a backward walk over the bytecode from an instruction,
   * counting stack effects, to the instruction that pushed the value; copies ({@code DUP}, {@code
   * CHECKCAST}) and locals (the stores of an {@code ALOAD}'s variable) are followed, and at a
   * branch merge both incoming paths are searched. A method parameter appears as its {@code ALOAD},
   * a caught exception as its handler label, anything the walk cannot follow as the instruction
   * where it stopped (always treated as unknown, never as safe).
   */
  @SuppressWarnings("ReferenceEquality") // instruction and label identity
  static final class Flow {
    private final MethodNode method;
    private final Set<LabelNode> handlers = new LinkedHashSet<>();

    Flow(MethodNode method) {
      this.method = method;
      for (TryCatchBlockNode block : method.tryCatchBlocks) {
        handlers.add(block.handler);
      }
    }

    /**
     * The origins of the value at depth {@code depth} (0 = top) of the operand stack just before
     * {@code insn}.
     */
    Set<AbstractInsnNode> valueOf(AbstractInsnNode insn, int depth) {
      Set<AbstractInsnNode> origins = new LinkedHashSet<>();
      search(insn, depth, origins, 0, new HashSet<>());
      return origins;
    }

    private void search(
        AbstractInsnNode before,
        int depth,
        Set<AbstractInsnNode> out,
        int level,
        Set<String> visited) {
      if (level > MAX_DEPTH) {
        out.add(before);
        return;
      }
      if (!visited.add(method.instructions.indexOf(before) + ":" + depth)) {
        return; // already searched (loops)
      }
      int need = depth;
      AbstractInsnNode walk = before.getPrevious();
      while (walk != null) {
        if (walk instanceof LabelNode label) {
          if (handlers.contains(label) && need == 0) {
            out.add(label); // the caught exception
            return;
          }
          for (AbstractInsnNode jump : jumpsTo(label)) {
            search(jump, need + effect(jump)[0], out, level + 1, visited);
          }
          AbstractInsnNode previous = previousInstruction(label);
          if (previous == null || endsFlow(previous)) {
            return;
          }
          walk = walk.getPrevious();
          continue;
        }
        if (walk.getOpcode() < 0) {
          walk = walk.getPrevious(); // line numbers, frames
          continue;
        }
        int[] effect = effect(walk);
        if (effect == null) {
          out.add(walk); // DUP_X1, SWAP, ...: not followed
          return;
        }
        if (need < effect[1]) {
          follow(walk, out, level, visited);
          return;
        }
        need = need - effect[1] + effect[0];
        walk = walk.getPrevious();
      }
    }

    /** {@code producer} pushed the value; copies are followed to their source. */
    private void follow(
        AbstractInsnNode producer, Set<AbstractInsnNode> out, int level, Set<String> visited) {
      switch (producer.getOpcode()) {
        case Opcodes.DUP, Opcodes.CHECKCAST -> search(producer, 0, out, level + 1, visited);
        case Opcodes.ALOAD -> {
          List<AbstractInsnNode> stores = storesOf((VarInsnNode) producer);
          if (stores.isEmpty()) {
            out.add(producer); // a parameter (or this)
          }
          for (AbstractInsnNode store : stores) {
            search(store, 0, out, level + 1, visited);
          }
        }
        default -> out.add(producer);
      }
    }

    /**
     * The {@code ASTORE}s that may have set the variable {@code load} reads. With a local variable
     * table (javac -g) only the stores of the variable whose scope holds the load: its
     * initialization right before the scope and the assignments inside it, so that another variable
     * sharing the slot (javac reuses slots) does not count; without one, every store of the slot.
     */
    private List<AbstractInsnNode> storesOf(VarInsnNode load) {
      InsnList insns = method.instructions;
      int at = insns.indexOf(load);
      LocalVariableNode scope = null;
      if (method.localVariables != null) {
        for (LocalVariableNode local : method.localVariables) {
          if (local.index == load.var
              && insns.indexOf(local.start) <= at
              && at < insns.indexOf(local.end)) {
            scope = local;
          }
        }
      }
      List<AbstractInsnNode> stores = new ArrayList<>();
      AbstractInsnNode initialization = null;
      for (AbstractInsnNode insn : insns) {
        if (insn.getOpcode() != Opcodes.ASTORE || ((VarInsnNode) insn).var != load.var) {
          continue;
        }
        if (scope == null) {
          stores.add(insn);
          continue;
        }
        int index = insns.indexOf(insn);
        if (index < insns.indexOf(scope.start)) {
          initialization = insn;
        } else if (index < insns.indexOf(scope.end)) {
          stores.add(insn);
        }
      }
      if (initialization != null) {
        stores.add(0, initialization);
      }
      return stores;
    }

    /** The AASTOREs into one of {@code arrays} between the array's creation and {@code call}. */
    List<AbstractInsnNode> storesInto(Set<AbstractInsnNode> arrays, AbstractInsnNode call) {
      List<AbstractInsnNode> stores = new ArrayList<>();
      for (AbstractInsnNode insn : method.instructions) {
        if (insn == call) {
          break;
        }
        if (insn.getOpcode() == Opcodes.AASTORE) {
          Set<AbstractInsnNode> target = valueOf(insn, 2);
          if (target.stream().anyMatch(arrays::contains)) {
            stores.add(insn);
          }
        }
      }
      return stores;
    }

    private List<AbstractInsnNode> jumpsTo(LabelNode label) {
      List<AbstractInsnNode> jumps = new ArrayList<>();
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof JumpInsnNode jump && jump.label == label) {
          jumps.add(insn);
        } else if (insn instanceof TableSwitchInsnNode table
            && (table.dflt == label || table.labels.contains(label))) {
          jumps.add(insn);
        } else if (insn instanceof LookupSwitchInsnNode lookup
            && (lookup.dflt == label || lookup.labels.contains(label))) {
          jumps.add(insn);
        }
      }
      return jumps;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode node) {
      AbstractInsnNode walk = node.getPrevious();
      while (walk != null && walk.getOpcode() < 0) {
        walk = walk.getPrevious();
      }
      return walk;
    }

    private static boolean endsFlow(AbstractInsnNode insn) {
      int opcode = insn.getOpcode();
      return opcode == Opcodes.GOTO
          || opcode == Opcodes.ATHROW
          || opcode == Opcodes.TABLESWITCH
          || opcode == Opcodes.LOOKUPSWITCH
          || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN);
    }

    /** Why {@code origin} may carry collected data, or empty when it cannot. */
    Optional<String> unsafe(AbstractInsnNode origin, LogAllowlist allowlist, int level) {
      if (level > MAX_DEPTH) {
        return Optional.of(describe(origin) + " (too deep to follow)");
      }
      if (origin instanceof LabelNode) {
        return Optional.empty(); // a caught exception
      }
      return switch (origin.getOpcode()) {
        case Opcodes.LDC, Opcodes.ACONST_NULL -> Optional.empty();
        case Opcodes.NEW -> unsafeType(((TypeInsnNode) origin).desc);
        case Opcodes.GETSTATIC, Opcodes.GETFIELD -> unsafeField((FieldInsnNode) origin, allowlist);
        case Opcodes.INVOKESTATIC,
            Opcodes.INVOKEVIRTUAL,
            Opcodes.INVOKEINTERFACE,
            Opcodes.INVOKESPECIAL ->
            unsafeCall((MethodInsnNode) origin, allowlist, level);
        case Opcodes.ALOAD -> unsafeParameter(((VarInsnNode) origin).var);
        default -> Optional.of(describe(origin));
      };
    }

    private static Optional<String> unsafeType(String internalName) {
      return safeType(internalName)
          ? Optional.empty()
          : Optional.of("new " + internalName.replace('/', '.'));
    }

    private static Optional<String> unsafeField(FieldInsnNode field, LogAllowlist allowlist) {
      Type type = Type.getType(field.desc);
      if (type.getSort() == Type.OBJECT && safeType(type.getInternalName())) {
        return Optional.empty();
      }
      String name = field.owner.replace('/', '.') + "#" + field.name;
      return allowlist.allowsProducer(name)
          ? Optional.empty()
          : Optional.of("field " + name + " (" + type.getClassName() + ")");
    }

    /** A parameter (copies of locals were followed): its declared type decides. */
    private Optional<String> unsafeParameter(int var) {
      Optional<Type> type = parameterType(var);
      if (type.isPresent()
          && type.get().getSort() == Type.OBJECT
          && safeType(type.get().getInternalName())) {
        return Optional.empty();
      }
      return Optional.of(
          "parameter #"
              + var
              + type.map(t -> " (" + t.getClassName() + ")").orElse("")
              + " of unknown origin");
    }

    private Optional<String> unsafeCall(MethodInsnNode call, LogAllowlist allowlist, int level) {
      Type returned = Type.getReturnType(call.desc);
      Type[] arguments = Type.getArgumentTypes(call.desc);
      if (BOXED.contains(call.owner) && call.name.equals("valueOf")) {
        return Optional.empty();
      }
      if (returned.getSort() == Type.OBJECT && safeType(returned.getInternalName())) {
        return Optional.empty();
      }
      if (JDK_PRODUCERS.contains(call.owner + "#" + call.name)
          || allowlist.allowsProducer(call.owner.replace('/', '.') + "#" + call.name)) {
        return Optional.empty();
      }
      // String.valueOf(primitive) is a number; String.valueOf(Object) is its argument.
      if (call.owner.equals("java/lang/String") && call.name.equals("valueOf")) {
        if (arguments.length == 1 && arguments[0].getSort() != Type.OBJECT) {
          return Optional.empty();
        }
        return firstProblem(valueOf(call, arguments.length - 1), allowlist, level + 1);
      }
      // toString()/getMessage() of a throwable: the throwable itself is accepted.
      if ((call.name.equals("toString") || call.name.equals("getMessage"))
          && arguments.length == 0
          && firstProblem(valueOf(call, 0), allowlist, level + 1).isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          "a call of "
              + call.owner.replace('/', '.')
              + "#"
              + call.name
              + " ("
              + returned.getClassName()
              + ")");
    }

    private Optional<String> firstProblem(
        Set<AbstractInsnNode> origins, LogAllowlist allowlist, int level) {
      for (AbstractInsnNode origin : origins) {
        Optional<String> problem = unsafe(origin, allowlist, level);
        if (problem.isPresent()) {
          return problem;
        }
      }
      return Optional.empty();
    }

    /** The declared type of the parameter in local slot {@code slot} (none for {@code this}). */
    Optional<Type> parameterType(int slot) {
      int next = 0;
      if ((method.access & Opcodes.ACC_STATIC) == 0) {
        if (slot == 0) {
          return Optional.empty();
        }
        next = 1;
      }
      for (Type argument : Type.getArgumentTypes(method.desc)) {
        if (next == slot) {
          return Optional.of(argument);
        }
        next += argument.getSize();
      }
      return Optional.empty();
    }
  }

  /**
   * {@code {values popped, values pushed}} of an instruction (long and double count as one value);
   * null for the stack shuffles the walk does not follow.
   */
  static int[] effect(AbstractInsnNode insn) {
    int opcode = insn.getOpcode();
    if (insn instanceof MethodInsnNode call) {
      int pops = Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
      return new int[] {pops, Type.getReturnType(call.desc).getSort() == Type.VOID ? 0 : 1};
    }
    if (insn instanceof InvokeDynamicInsnNode indy) {
      return new int[] {
        Type.getArgumentTypes(indy.desc).length,
        Type.getReturnType(indy.desc).getSort() == Type.VOID ? 0 : 1
      };
    }
    if (insn instanceof MultiANewArrayInsnNode array) {
      return new int[] {array.dims, 1};
    }
    if (opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.ALOAD) {
      return new int[] {0, 1}; // constants, LDC, loads
    }
    if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
      return new int[] {2, 1};
    }
    if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
      return new int[] {1, 0};
    }
    if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
      return new int[] {3, 0};
    }
    if ((opcode >= Opcodes.IADD && opcode <= Opcodes.DREM)
        || (opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR)
        || (opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG)) {
      return new int[] {2, 1};
    }
    if ((opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG)
        || (opcode >= Opcodes.I2L && opcode <= Opcodes.I2S)) {
      return new int[] {1, 1};
    }
    if ((opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE)
        || opcode == Opcodes.IFNULL
        || opcode == Opcodes.IFNONNULL) {
      return new int[] {1, 0};
    }
    if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE) {
      return new int[] {2, 0};
    }
    if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
      return new int[] {1, 0};
    }
    return switch (opcode) {
      case Opcodes.NOP, Opcodes.IINC, Opcodes.GOTO, Opcodes.RETURN -> new int[] {0, 0};
      case Opcodes.POP,
          Opcodes.TABLESWITCH,
          Opcodes.LOOKUPSWITCH,
          Opcodes.ATHROW,
          Opcodes.MONITORENTER,
          Opcodes.MONITOREXIT,
          Opcodes.PUTSTATIC ->
          new int[] {1, 0};
      case Opcodes.DUP -> new int[] {1, 2};
      case Opcodes.GETSTATIC, Opcodes.NEW -> new int[] {0, 1};
      case Opcodes.GETFIELD,
          Opcodes.NEWARRAY,
          Opcodes.ANEWARRAY,
          Opcodes.ARRAYLENGTH,
          Opcodes.CHECKCAST,
          Opcodes.INSTANCEOF ->
          new int[] {1, 1};
      case Opcodes.PUTFIELD -> new int[] {2, 0};
      default -> null; // POP2, DUP_X1, DUP_X2, DUP2*, SWAP, JSR, RET
    };
  }

  /** Boxed numbers, enums, throwables and {@link #SAFE_TYPES}. */
  static boolean safeType(String internalName) {
    if (BOXED.contains(internalName) || SAFE_TYPES.contains(internalName)) {
      return true;
    }
    Optional<Class<?>> type = load(internalName);
    return type.isPresent()
        && (type.get().isEnum()
            || Throwable.class.isAssignableFrom(type.get())
            || Number.class.isAssignableFrom(type.get()));
  }

  static Optional<Class<?>> load(String internalName) {
    try {
      return Optional.of(
          Class.forName(
              internalName.replace('/', '.'), false, AgentCodeRules.class.getClassLoader()));
    } catch (ClassNotFoundException | LinkageError e) {
      return Optional.empty();
    }
  }

  static String describe(AbstractInsnNode insn) {
    if (insn instanceof VarInsnNode var && insn.getOpcode() == Opcodes.ALOAD) {
      return "local variable or parameter #" + var.var + " of unknown origin";
    }
    if (insn instanceof MethodInsnNode call) {
      return "a call of " + call.owner.replace('/', '.') + "#" + call.name;
    }
    if (insn instanceof FieldInsnNode field) {
      return "field " + field.owner.replace('/', '.') + "#" + field.name;
    }
    if (insn.getOpcode() == Opcodes.AALOAD) {
      return "an array element";
    }
    return "opcode " + insn.getOpcode();
  }

  static String where(JavaClass javaClass, MethodNode method, int line) {
    return javaClass.getName()
        + "."
        + method.name
        + (line > 0 ? " (" + javaClass.getSimpleName() + ".java:" + line + ")" : "");
  }

  // --- A-7 -------------------------------------------------------------------------------------

  /** A-7 [10 §5.3]: constructors get exit advice only. */
  static ArchRule constructorsGetExitAdviceOnly(AgentLayout layout) {
    String transformer = layout.instrumentationApi().replace('.', '/') + "/TypeTransformer";
    DescribedPredicate<JavaClass> instrumentation =
        DescribedPredicate.describe(
            "instrumentation module classes",
            c ->
                layout.moduleOf(c.getName()).isPresent()
                    && !AgentLayout.inPackage(c.getName(), layout.instrumentationApi()));
    return classes()
        .that(instrumentation)
        .should(
            new ArchCondition<JavaClass>(
                "apply only advice without @Advice.OnMethodEnter to constructor matchers") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (String violation : constructorViolations(layout, item, transformer)) {
                  events.add(SimpleConditionEvent.violated(item, violation));
                }
              }
            })
        .as("A-7 [10 §5.3] constructors get exit advice only (no @Advice.OnMethodEnter)")
        .because(
            "enter advice of a constructor runs before the super constructor call, where this is"
                + " not initialized; 10 §5.3 allows OnMethodExit only");
  }

  static List<String> constructorViolations(
      AgentLayout layout, JavaClass item, String transformer) {
    List<String> violations = new ArrayList<>();
    ClassNode node = AdviceBytecode.read(item);
    AdviceUse use = adviceUse(layout, item);
    for (String advice : use.constructors()) {
      // ByteBuddy rejects exception handling in a constructor ("Cannot catch exception during
      // constructor call"): the transformation would fail at load time (WO-13).
      if (hasOnThrowableExit(layout, advice)) {
        violations.add(
            item.getName()
                + ": constructor advice "
                + advice
                + " declares onThrowable on @Advice.OnMethodExit (ByteBuddy cannot catch inside a"
                + " constructor)");
      }
      if (use.methods().contains(advice)) {
        violations.add(
            item.getName()
                + ": advice "
                + advice
                + " is applied to constructors and to methods (constructor exit advice has no"
                + " onThrowable, method exit advice needs it)");
      }
    }
    for (MethodNode method : node.methods) {
      Flow flow = new Flow(method);
      int line = -1;
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof LineNumberNode lineNumber) {
          line = lineNumber.line;
          continue;
        }
        if (!(insn instanceof MethodInsnNode call)
            || !call.owner.equals(transformer)
            || !call.name.equals("applyAdvice")) {
          continue;
        }
        if (!matchesConstructors(node, flow, flow.valueOf(call, 1), 0)) {
          continue;
        }
        String at = where(item, method, line) + ": constructor advice ";
        for (AbstractInsnNode origin : flow.valueOf(call, 0)) {
          Optional<String> advice = adviceClassName(flow, origin);
          if (advice.isEmpty()) {
            violations.add(at + "is not named by a class literal (" + describe(origin) + ")");
          } else if (hasEnterAdvice(layout, advice.get())) {
            violations.add(at + advice.get() + " declares @Advice.OnMethodEnter");
          }
        }
      }
    }
    return violations;
  }

  /**
   * The advice classes {@code item} applies ({@code TypeTransformer.applyAdvice} calls whose advice
   * is a class literal or constant): those applied to constructor matchers and those applied to
   * other matchers.
   */
  record AdviceUse(Set<String> constructors, Set<String> methods) {}

  static AdviceUse adviceUse(AgentLayout layout, JavaClass item) {
    String transformer = layout.instrumentationApi().replace('.', '/') + "/TypeTransformer";
    Set<String> constructors = new LinkedHashSet<>();
    Set<String> methods = new LinkedHashSet<>();
    ClassNode node = AdviceBytecode.read(item);
    for (MethodNode method : node.methods) {
      Flow flow = new Flow(method);
      for (AbstractInsnNode insn : method.instructions) {
        if (!(insn instanceof MethodInsnNode call)
            || !call.owner.equals(transformer)
            || !call.name.equals("applyAdvice")) {
          continue;
        }
        boolean constructor = matchesConstructors(node, flow, flow.valueOf(call, 1), 0);
        for (AbstractInsnNode origin : flow.valueOf(call, 0)) {
          adviceClassName(flow, origin)
              .ifPresent(name -> (constructor ? constructors : methods).add(name));
        }
      }
    }
    return new AdviceUse(constructors, methods);
  }

  /**
   * The advice classes applied to constructors only by the instrumentation classes of {@code
   * advice}'s package (T-AG-002b: their exit advice has no {@code onThrowable}, A-7).
   */
  static Set<String> constructorOnlyAdvice(AgentLayout layout, JavaClass advice) {
    Set<String> constructors = new LinkedHashSet<>();
    Set<String> methods = new LinkedHashSet<>();
    for (JavaClass sibling : advice.getPackage().getClasses()) {
      if (!layout.moduleOf(sibling.getName()).isPresent()) {
        continue;
      }
      AdviceUse use = adviceUse(layout, sibling);
      constructors.addAll(use.constructors());
      methods.addAll(use.methods());
    }
    constructors.removeAll(methods);
    return constructors;
  }

  /** True when an {@code @Advice.OnMethodExit} of {@code className} sets {@code onThrowable}. */
  static boolean hasOnThrowableExit(AgentLayout layout, String className) {
    Optional<Class<?>> type = load(className.replace('.', '/'));
    ClassNode node = type.map(AgentCodeRules::readClass).orElse(null);
    if (node == null) {
      return false;
    }
    for (MethodNode method : node.methods) {
      List<AnnotationNode> annotations = new ArrayList<>();
      if (method.visibleAnnotations != null) {
        annotations.addAll(method.visibleAnnotations);
      }
      if (method.invisibleAnnotations != null) {
        annotations.addAll(method.invisibleAnnotations);
      }
      for (AnnotationNode annotation : annotations) {
        if (!layout
                .adviceMemberName(Type.getType(annotation.desc).getClassName())
                .equals(Optional.of(AgentPredicates.ON_METHOD_EXIT))
            || annotation.values == null) {
          continue;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
          if ("onThrowable".equals(annotation.values.get(i))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** True when one of {@code origins} builds a constructor matcher (followed through calls). */
  static boolean matchesConstructors(
      ClassNode node, Flow flow, Set<AbstractInsnNode> origins, int level) {
    if (level > MAX_DEPTH) {
      return false;
    }
    for (AbstractInsnNode origin : origins) {
      if (origin instanceof MethodInsnNode call) {
        if (isConstructorMatcher(call, flow)) {
          return true;
        }
        if (call.owner.equals(node.name) && referencesConstructorMatcher(node, call.name)) {
          return true;
        }
        int operands =
            Type.getArgumentTypes(call.desc).length
                + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        for (int depth = 0; depth < operands; depth++) {
          if (matchesConstructors(node, flow, flow.valueOf(call, depth), level + 1)) {
            return true;
          }
        }
      } else if (origin instanceof FieldInsnNode field
          && field.owner.equals(node.name)
          && referencesConstructorMatcher(node, null)) {
        return true;
      }
    }
    return false;
  }

  /** {@code ElementMatchers.isConstructor()} or {@code named("<init>")}. */
  private static boolean isConstructorMatcher(MethodInsnNode call, Flow flow) {
    if (!call.owner.endsWith("net/bytebuddy/matcher/ElementMatchers")) {
      return false;
    }
    if (call.name.equals("isConstructor")) {
      return true;
    }
    if (call.name.equals("named")) {
      for (AbstractInsnNode origin : flow.valueOf(call, 0)) {
        if (origin instanceof LdcInsnNode ldc && "<init>".equals(ldc.cst)) {
          return true;
        }
      }
    }
    return false;
  }

  /** True when method {@code name} of the class (any method when null) refers to a ctor matcher. */
  private static boolean referencesConstructorMatcher(ClassNode node, String name) {
    for (MethodNode method : node.methods) {
      if (name != null && !method.name.equals(name)) {
        continue;
      }
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof MethodInsnNode call
            && call.owner.endsWith("net/bytebuddy/matcher/ElementMatchers")
            && call.name.equals("isConstructor")) {
          return true;
        }
        if (insn instanceof LdcInsnNode ldc && "<init>".equals(ldc.cst)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The advice class of {@code X.class.getName()} or of a constant class name. */
  static Optional<String> adviceClassName(Flow flow, AbstractInsnNode origin) {
    if (origin instanceof LdcInsnNode ldc && ldc.cst instanceof String name) {
      return Optional.of(name);
    }
    if (origin instanceof MethodInsnNode call
        && call.owner.equals("java/lang/Class")
        && call.name.equals("getName")) {
      for (AbstractInsnNode receiver : flow.valueOf(call, 0)) {
        if (receiver instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
          return Optional.of(type.getClassName());
        }
      }
    }
    return Optional.empty();
  }

  static boolean hasEnterAdvice(AgentLayout layout, String className) {
    Optional<Class<?>> type = load(className.replace('.', '/'));
    ClassNode node = type.map(AgentCodeRules::readClass).orElse(null);
    if (node == null) {
      return true; // cannot prove exit-only
    }
    for (MethodNode method : node.methods) {
      List<AnnotationNode> annotations = new ArrayList<>();
      if (method.visibleAnnotations != null) {
        annotations.addAll(method.visibleAnnotations);
      }
      if (method.invisibleAnnotations != null) {
        annotations.addAll(method.invisibleAnnotations);
      }
      for (AnnotationNode annotation : annotations) {
        if (layout
            .adviceMemberName(Type.getType(annotation.desc).getClassName())
            .equals(Optional.of(AgentPredicates.ON_METHOD_ENTER))) {
          return true;
        }
      }
    }
    return false;
  }

  /** The class file of a loaded class (its class loader's resource), or null. */
  static ClassNode readClass(Class<?> type) {
    String resource = type.getName().replace('.', '/') + ".class";
    ClassLoader loader = type.getClassLoader();
    try (InputStream in =
        loader == null
            ? ClassLoader.getSystemResourceAsStream(resource)
            : loader.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      ClassNode node = new ClassNode();
      new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
      return node;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- A-9 -------------------------------------------------------------------------------------

  /** A-9 [10 §5.3, §8]: ContextStore value types. */
  static ArchRule contextStoreValuesAreSafe(AgentLayout layout) {
    String store = layout.bootstrap().replace('.', '/') + "/ContextStore";
    DescribedPredicate<JavaClass> instrumentation =
        DescribedPredicate.describe(
            "instrumentation module classes", c -> layout.moduleOf(c.getName()).isPresent());
    return classes()
        .that(instrumentation)
        .should(
            new ArchCondition<JavaClass>(
                "create ContextStores whose value type is a bootstrap type, boxed primitive,"
                    + " String, long[] or WeakReference") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (String violation : storeViolations(layout, item, store)) {
                  events.add(SimpleConditionEvent.violated(item, violation));
                }
              }
            })
        .as(
            "A-9 [10 §5.3] ContextStore values are bootstrap types, boxed primitives, String,"
                + " long[] or WeakReference")
        .because(
            "a store value that references an application object pins its class loader (F-A36,"
                + " 10 §8)");
  }

  static List<String> storeViolations(AgentLayout layout, JavaClass item, String store) {
    List<String> violations = new ArrayList<>();
    ClassNode node = AdviceBytecode.read(item);
    for (MethodNode method : node.methods) {
      Flow flow = new Flow(method);
      int line = -1;
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof LineNumberNode lineNumber) {
          line = lineNumber.line;
          continue;
        }
        if (!(insn instanceof MethodInsnNode call)
            || !call.owner.equals(store)
            || !call.name.equals("of")
            || Type.getArgumentTypes(call.desc).length != 2) {
          continue;
        }
        String at = where(item, method, line) + ": ContextStore value type ";
        for (AbstractInsnNode origin : flow.valueOf(call, 0)) {
          if (!(origin instanceof LdcInsnNode ldc && ldc.cst instanceof Type type)) {
            violations.add(at + "is not a class literal (" + describe(origin) + ")");
          } else if (!storeValueAllowed(layout, type)) {
            violations.add(at + type.getClassName() + " may hold an application object");
          }
        }
      }
    }
    return violations;
  }

  static boolean storeValueAllowed(AgentLayout layout, Type type) {
    if (type.getSort() == Type.ARRAY) {
      return type.getDescriptor().equals("[J");
    }
    String name = type.getClassName();
    return BOXED.contains(type.getInternalName())
        || name.equals("java.lang.String")
        || name.equals("java.lang.ref.WeakReference")
        || AgentLayout.inPackage(name, layout.bootstrap());
  }
}
