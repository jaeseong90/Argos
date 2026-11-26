/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.ContextCarrier;
import io.argos.agent.bootstrap.TraceContext;
import java.io.Serializable;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * The context field of DSN-02-05 (10 §6.6 "필드 주입"): adds {@code private volatile transient
 * TraceContext argos$ctx}, the interface {@link ContextCarrier} and its two accessors to a class
 * that a module asked for with {@code TypeTransformer.injectContextField()}.
 *
 * <p>The agent builder decorates every type ({@code DECORATE}: no class format change through
 * ByteBuddy's instrumented type); this wrapper writes the members at the ASM level, and only where
 * the class format may still change ({@link #eligible}):
 *
 * <ul>
 *   <li>the class is being defined for the first time: ByteBuddy's {@code HYBRID} description
 *       strategy describes a class being retransformed by its loaded {@link Class} ({@link
 *       TypeDescription.ForLoadedType}), which is never given new members. Exception: a loaded
 *       class that already declares the field got it at its definition, and a later
 *       retransformation (by another agent) must reproduce the same schema, so it is written again
 *       (the visitor adds only what is missing);
 *   <li>an application class loader (not the bootstrap or platform loader), a class outside {@code
 *       java.}, {@code jdk.}, {@code sun.}, {@code com.sun.} and {@code io.argos.}, in no named
 *       module (a named module would need a read edge before its superinterface resolves);
 *   <li>a concrete class: no interface, annotation, enum or record;
 *   <li>not {@link Serializable}: a new interface and new methods change the default {@code
 *       serialVersionUID}, which would break deserialization against JVMs without the agent.
 * </ul>
 *
 * Every other type keeps its class format, and the task stores fall back to the weak map. The
 * generated members are synthetic, and the accessors are straight-line code without frames, valid
 * in every class file version and under {@code -XX:+BytecodeVerificationLocal}.
 */
final class FieldInjector extends AsmVisitorWrapper.AbstractBase {
  static final FieldInjector INSTANCE = new FieldInjector();

  private static final String CARRIER = ContextCarrier.class.getName().replace('.', '/');
  private static final String CONTEXT_DESCRIPTOR =
      "L" + TraceContext.class.getName().replace('.', '/') + ";";
  private static final String GETTER_DESCRIPTOR = "()" + CONTEXT_DESCRIPTOR;
  private static final String SETTER_DESCRIPTOR = "(" + CONTEXT_DESCRIPTOR + ")V";

  private static final ClassLoader PLATFORM_LOADER = platformLoader();

  private FieldInjector() {}

  private static ClassLoader platformLoader() {
    try {
      ClassLoader system = ClassLoader.getSystemClassLoader();
      return system == null ? null : system.getParent();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Whether the context field may be added to {@code type} defined by {@code loader} in {@code
   * module} (see the class comment). Never throws: a supertype that cannot be resolved means "no".
   */
  @SuppressWarnings("ReferenceEquality") // class loaders are compared by identity
  static boolean eligible(TypeDescription type, ClassLoader loader, JavaModule module) {
    try {
      if (loader == null || loader == PLATFORM_LOADER) {
        return false;
      }
      if (type instanceof TypeDescription.ForLoadedType) {
        return declaresField(type);
      }
      if (type.isInterface()
          || type.isAnnotation()
          || type.isEnum()
          || type.isRecord()
          || type.isArray()
          || type.isPrimitive()) {
        return false;
      }
      String name = type.getName();
      if (name.startsWith("java.")
          || name.startsWith("jdk.")
          || name.startsWith("sun.")
          || name.startsWith("com.sun.")
          || name.startsWith("io.argos.")) {
        return false;
      }
      if (module != null && Jpms.SUPPORTED && Jpms.isNamed(module.unwrap())) {
        return false;
      }
      if (declaresField(type)) {
        return false;
      }
      return !type.isAssignableTo(Serializable.class);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean declaresField(TypeDescription type) {
    for (FieldDescription.InDefinedShape field : type.getDeclaredFields()) {
      if (field.getName().equals(ContextCarrier.FIELD_NAME)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    return new Injecting(classVisitor);
  }

  /**
   * Adds the interface on {@code visit} and the field and accessors on {@code visitEnd}, each only
   * if the class does not have it yet (idempotent, so a retransformation that already writes some
   * of them never produces a duplicate member).
   */
  static final class Injecting extends ClassVisitor {
    private String owner;
    private boolean hasField;
    private boolean hasGetter;
    private boolean hasSetter;

    Injecting(ClassVisitor delegate) {
      super(OpenedClassReader.ASM_API, delegate);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      owner = name;
      String[] withCarrier = interfaces;
      String withCarrierSignature = signature;
      if (!contains(interfaces, CARRIER)) {
        int count = interfaces == null ? 0 : interfaces.length;
        withCarrier = new String[count + 1];
        if (count > 0) {
          System.arraycopy(interfaces, 0, withCarrier, 0, count);
        }
        withCarrier[count] = CARRIER;
        if (signature != null) {
          // ClassSignature: [TypeParameters] SuperclassSignature {SuperinterfaceSignature}.
          withCarrierSignature = signature + "L" + CARRIER + ";";
        }
      }
      super.visit(version, access, name, withCarrierSignature, superName, withCarrier);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (name.equals(ContextCarrier.FIELD_NAME)) {
        hasField = true;
      }
      return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (name.equals(ContextCarrier.GETTER_NAME) && descriptor.equals(GETTER_DESCRIPTOR)) {
        hasGetter = true;
      } else if (name.equals(ContextCarrier.SETTER_NAME) && descriptor.equals(SETTER_DESCRIPTOR)) {
        hasSetter = true;
      }
      return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
      if (!hasField) {
        FieldVisitor field =
            super.visitField(
                Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_VOLATILE
                    | Opcodes.ACC_TRANSIENT
                    | Opcodes.ACC_SYNTHETIC,
                ContextCarrier.FIELD_NAME,
                CONTEXT_DESCRIPTOR,
                null,
                null);
        if (field != null) {
          field.visitEnd();
        }
      }
      if (!hasGetter) {
        MethodVisitor getter =
            super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                ContextCarrier.GETTER_NAME,
                GETTER_DESCRIPTOR,
                null,
                null);
        if (getter != null) {
          getter.visitCode();
          getter.visitVarInsn(Opcodes.ALOAD, 0);
          getter.visitFieldInsn(
              Opcodes.GETFIELD, owner, ContextCarrier.FIELD_NAME, CONTEXT_DESCRIPTOR);
          getter.visitInsn(Opcodes.ARETURN);
          getter.visitMaxs(1, 1);
          getter.visitEnd();
        }
      }
      if (!hasSetter) {
        MethodVisitor setter =
            super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                ContextCarrier.SETTER_NAME,
                SETTER_DESCRIPTOR,
                null,
                null);
        if (setter != null) {
          setter.visitCode();
          setter.visitVarInsn(Opcodes.ALOAD, 0);
          setter.visitVarInsn(Opcodes.ALOAD, 1);
          setter.visitFieldInsn(
              Opcodes.PUTFIELD, owner, ContextCarrier.FIELD_NAME, CONTEXT_DESCRIPTOR);
          setter.visitInsn(Opcodes.RETURN);
          setter.visitMaxs(2, 2);
          setter.visitEnd();
        }
      }
      super.visitEnd();
    }

    private static boolean contains(String[] values, String value) {
      if (values != null) {
        for (String candidate : values) {
          if (value.equals(candidate)) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
