/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.TransactionKind;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proto contract test (b): the protocol enums carry exactly the numbers of the agent bootstrap
 * constants (02 §7, C-1, C-16, ADR-0010 C-101), so the agent can put its int constants on the wire
 * without a mapping table. A value added on one side only fails here.
 *
 * <p>The protocol names differ from the bootstrap names only where 11 §4.1 needed a package-unique
 * enum value name ({@link #RENAMED}).
 */
class BootstrapConstantsContractTest {

  /** Protocol enum value name -> bootstrap constant name, where they differ. */
  private static final Map<String, String> RENAMED;

  static {
    Map<String, String> renamed = new HashMap<>();
    renamed.put("StepType.HTTP_CALL", "HTTP");
    renamed.put("StepType.MESSAGE_STEP", "MESSAGE");
    renamed.put("DictionaryKind.SQL_TEXT", "SQL");
    renamed.put("DictionaryKind.METHOD_NAME", "METHOD");
    renamed.put("FailureKind.FAILURE_NONE", "NONE");
    renamed.put("FailureKind.OTHER_ERROR", "OTHER");
    RENAMED = Collections.unmodifiableMap(renamed);
  }

  @Test
  @Tag("req:C-1")
  @DisplayName(
      "StepType numbers equal the bootstrap StepType constants (C-1: SQL=1 ... SUMMARY=11)")
  void stepTypes() throws IllegalAccessException {
    assertThat(protocolNumbers(io.argos.proto.v1.StepType.getDescriptor()))
        .isEqualTo(bootstrapConstants(StepType.class));
  }

  @Test
  @Tag("req:C-16")
  @Tag("req:DSN-02-10")
  @DisplayName(
      "DictionaryKind numbers equal the bootstrap DictKind constants (C-16 1..11, C-101 POOL=12)")
  void dictionaryKinds() throws IllegalAccessException {
    assertThat(protocolNumbers(DictionaryKind.getDescriptor()))
        .isEqualTo(bootstrapConstants(DictKind.class));
    int last = DictionaryKind.getDescriptor().getValues().size() - 1;
    assertThat(DictKind.COUNT).as("DictKind.COUNT = last kind + 1").isEqualTo(last + 1);
    assertThat(DictKind.name(DictionaryKind.POOL_VALUE)).isEqualTo("POOL");
  }

  @Test
  @Tag("req:DSN-02-15")
  @DisplayName(
      "TransactionKind numbers equal the bootstrap Kind constants (HTTP=1 BATCH=2 MESSAGE=3)")
  void transactionKinds() throws IllegalAccessException {
    assertThat(protocolNumbers(TransactionKind.getDescriptor()))
        .isEqualTo(bootstrapConstants(Kind.class));
  }

  @Test
  @Tag("req:F-A05")
  @DisplayName("FailureKind numbers equal the bootstrap FailureKind constants (NONE=0 ... OTHER=4)")
  void failureKinds() throws IllegalAccessException {
    assertThat(protocolNumbers(io.argos.proto.v1.FailureKind.getDescriptor()))
        .isEqualTo(bootstrapConstants(FailureKind.class));
  }

  @Test
  @Tag("req:C-69")
  @DisplayName(
      "TransactionRecord.flags bits equal the bootstrap TraceContext flags (C-69: 1, 2, 4)")
  void transactionFlags() {
    assertThat(TransactionFlags.VIRTUAL_THREAD).isEqualTo(TraceContext.FLAG_VIRTUAL_THREAD).isOne();
    assertThat(TransactionFlags.LEAKED).isEqualTo(TraceContext.FLAG_LEAKED).isEqualTo(2);
    assertThat(TransactionFlags.SUMMARIZED).isEqualTo(TraceContext.FLAG_SUMMARIZED).isEqualTo(4);
    assertThat(TransactionFlags.MASK).isEqualTo(TraceContext.FLAG_MASK);
  }

  /**
   * Bootstrap-named numbers of a protocol enum: *_UNSPECIFIED is left out (the bootstrap tables
   * start at 1), FailureKind's FAILURE_NONE = 0 is kept (bootstrap NONE = 0).
   */
  private static Map<String, Integer> protocolNumbers(EnumDescriptor type) {
    Map<String, Integer> numbers = new TreeMap<>();
    for (EnumValueDescriptor value : type.getValues()) {
      if (value.getName().endsWith("_UNSPECIFIED")) {
        assertThat(value.getNumber()).as(value.getFullName()).isZero();
        continue;
      }
      String key = type.getName() + "." + value.getName();
      String name = RENAMED.containsKey(key) ? RENAMED.get(key) : value.getName();
      numbers.put(name, value.getNumber());
    }
    return numbers;
  }

  /** The public static final int constants of a bootstrap table, without its COUNT. */
  private static Map<String, Integer> bootstrapConstants(Class<?> table)
      throws IllegalAccessException {
    Map<String, Integer> constants = new TreeMap<>();
    for (Field field : table.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (Modifier.isPublic(modifiers)
          && Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)
          && field.getType() == int.class
          && !field.getName().equals("COUNT")) {
        constants.put(field.getName(), field.getInt(null));
      }
    }
    return constants;
  }
}
