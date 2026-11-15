/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import io.argos.proto.v1.CommonProto;
import io.argos.proto.v1.DataProto;
import io.argos.proto.v1.ServiceProto;
import io.argos.proto.v1.SessionProto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proto contract test (d): every argos.v1 enum, top-level or nested, in every file, has its zero
 * value named {@code <UPPER_SNAKE(EnumName)>_UNSPECIFIED} (11 §4.4, §12: receivers map unknown
 * values to {@code *_UNSPECIFIED}). The only exception is {@code FailureKind.FAILURE_NONE} (the
 * meaningful "no failure" of 11 §4.1, agent {@code FailureKind.NONE = 0}), which is also the only
 * buf lint comment ignore ({@code proto/buf.yaml}). A new enum in any file, {@code common.proto}
 * included, fails here even when buf is not run.
 */
@Tag("req:O-R02")
class EnumZeroValueTest {

  /** Enum full name -> zero value name, for the enums that do not follow the rule. */
  private static final Map<String, String> EXCEPTIONS =
      Collections.singletonMap("argos.v1.FailureKind", "FAILURE_NONE");

  private static final List<FileDescriptor> FILES =
      Arrays.asList(
          CommonProto.getDescriptor(),
          SessionProto.getDescriptor(),
          DataProto.getDescriptor(),
          ServiceProto.getDescriptor());

  @Test
  @DisplayName(
      "every argos.v1 enum (nested included) has the zero value <ENUM_NAME>_UNSPECIFIED;"
          + " FailureKind.FAILURE_NONE is the only listed exception (11 §4.4, §12)")
  void everyEnumHasAnUnspecifiedZeroValue() {
    Map<String, String> actual = new TreeMap<>();
    Map<String, String> expected = new TreeMap<>();
    for (EnumDescriptor type : allEnums()) {
      EnumValueDescriptor zero = type.findValueByNumber(0);
      assertThat(zero).as(type.getFullName() + " has a value numbered 0").isNotNull();
      actual.put(type.getFullName(), zero.getName());
      String exception = EXCEPTIONS.get(type.getFullName());
      expected.put(
          type.getFullName(),
          exception != null ? exception : upperSnake(type.getName()) + "_UNSPECIFIED");
    }
    assertThat(actual).isEqualTo(expected);
    assertThat(actual.keySet())
        .as("every listed exception names an existing enum")
        .containsAll(EXCEPTIONS.keySet());
  }

  @Test
  @DisplayName("the enum walk covers top-level and nested enums of all four argos.v1 files")
  void walkCoversEveryFile() {
    List<String> names = new ArrayList<>();
    for (EnumDescriptor type : allEnums()) {
      names.add(type.getFullName());
    }
    // One known enum per file that has enums (service.proto has none), so a file left out of
    // FILES or a broken walk cannot make the main test pass vacuously.
    assertThat(names)
        .contains(
            "argos.v1.TransactionKind",
            "argos.v1.FailureKind",
            "argos.v1.CommandType",
            "argos.v1.RejectReason",
            "argos.v1.AgentEventType",
            "argos.v1.AckStatus",
            "argos.v1.ActiveState");
    assertThat(names).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("UPPER_SNAKE conversion of enum names follows buf's ENUM_ZERO_VALUE_SUFFIX rule")
  void upperSnakeConversion() {
    assertThat(upperSnake("TransactionKind")).isEqualTo("TRANSACTION_KIND");
    assertThat(upperSnake("IpMode")).isEqualTo("IP_MODE");
    assertThat(upperSnake("UserIdMode")).isEqualTo("USER_ID_MODE");
    assertThat(upperSnake("AckStatus")).isEqualTo("ACK_STATUS");
    assertThat(upperSnake("HTTPStatus")).isEqualTo("HTTP_STATUS");
    assertThat(upperSnake("Kind2Name")).isEqualTo("KIND2_NAME");
  }

  private static List<EnumDescriptor> allEnums() {
    List<EnumDescriptor> enums = new ArrayList<>();
    for (FileDescriptor file : FILES) {
      enums.addAll(file.getEnumTypes());
      for (Descriptor message : file.getMessageTypes()) {
        collectNested(message, enums);
      }
    }
    return enums;
  }

  private static void collectNested(Descriptor message, List<EnumDescriptor> out) {
    out.addAll(message.getEnumTypes());
    for (Descriptor nested : message.getNestedTypes()) {
      collectNested(nested, out);
    }
  }

  /**
   * {@code PascalCase} -> {@code UPPER_SNAKE}, splitting at every lower/digit-to-upper boundary.
   */
  static String upperSnake(String name) {
    StringBuilder out = new StringBuilder(name.length() + 8);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (i > 0 && Character.isUpperCase(c)) {
        char prev = name.charAt(i - 1);
        boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
        if (Character.isLowerCase(prev)
            || Character.isDigit(prev)
            || (Character.isUpperCase(prev) && nextLower)) {
          out.append('_');
        }
      }
      out.append(Character.toUpperCase(c));
    }
    return out.toString();
  }
}
