/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import io.argos.proto.v1.CommonProto;
import io.argos.proto.v1.DataProto;
import io.argos.proto.v1.ServiceProto;
import io.argos.proto.v1.SessionProto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the argos.v1 schema from the runtime descriptors as the lines of {@code
 * src/test/resources/argos-v1-fields.txt}: per file its package, Java options and imports, then its
 * enums ({@code enum.VALUE = number}, reservations), messages ({@code message.field = number, type,
 * label}, reservations) and services ({@code rpc ...}), each group in declaration order.
 */
final class FieldSnapshot {

  private FieldSnapshot() {}

  /** The four argos.v1 files in dependency order. */
  static List<FileDescriptor> files() {
    List<FileDescriptor> files = new ArrayList<>();
    files.add(CommonProto.getDescriptor());
    files.add(SessionProto.getDescriptor());
    files.add(DataProto.getDescriptor());
    files.add(ServiceProto.getDescriptor());
    return files;
  }

  /** The snapshot lines of the current descriptors (no comments, no blank lines). */
  static List<String> render() {
    List<String> out = new ArrayList<>();
    for (FileDescriptor file : files()) {
      renderFile(file, out);
    }
    return out;
  }

  private static void renderFile(FileDescriptor file, List<String> out) {
    StringBuilder imports = new StringBuilder();
    for (FileDescriptor dependency : file.getDependencies()) {
      imports.append(imports.length() == 0 ? "" : ", ").append(dependency.getName());
    }
    out.add(
        "file "
            + file.getName()
            + ": package "
            + file.getPackage()
            + ", java_package "
            + file.getOptions().getJavaPackage()
            + ", java_multiple_files "
            + file.getOptions().getJavaMultipleFiles()
            + ", java_outer_classname "
            + file.getOptions().getJavaOuterClassname()
            + ", imports ["
            + imports
            + "]");
    for (EnumDescriptor type : file.getEnumTypes()) {
      renderEnum(type, out);
    }
    for (Descriptor type : file.getMessageTypes()) {
      renderMessage(type, out);
    }
    for (ServiceDescriptor service : file.getServices()) {
      out.add("service " + service.getFullName());
      for (MethodDescriptor method : service.getMethods()) {
        out.add(rpc(method));
      }
    }
  }

  private static void renderEnum(EnumDescriptor type, List<String> out) {
    out.add("enum " + type.getFullName());
    for (EnumValueDescriptor value : type.getValues()) {
      out.add(value.getFullName() + " = " + value.getNumber());
    }
    EnumDescriptorProto proto = type.toProto();
    for (EnumDescriptorProto.EnumReservedRange range : proto.getReservedRangeList()) {
      // Enum reserved ranges are inclusive.
      out.add(type.getFullName() + " reserved " + range(range.getStart(), range.getEnd()));
    }
    for (String name : proto.getReservedNameList()) {
      out.add(type.getFullName() + " reserved \"" + name + "\"");
    }
  }

  private static void renderMessage(Descriptor type, List<String> out) {
    if (type.getOptions().getMapEntry()) {
      return; // synthesized map<k, v> entry: rendered as the map field's type
    }
    out.add("message " + type.getFullName());
    for (FieldDescriptor field : type.getFields()) {
      out.add(
          field.getFullName()
              + " = "
              + field.getNumber()
              + ", "
              + type(field)
              + ", "
              + label(field));
    }
    DescriptorProto proto = type.toProto();
    for (DescriptorProto.ReservedRange range : proto.getReservedRangeList()) {
      // Message reserved ranges are end-exclusive.
      out.add(type.getFullName() + " reserved " + range(range.getStart(), range.getEnd() - 1));
    }
    for (String name : proto.getReservedNameList()) {
      out.add(type.getFullName() + " reserved \"" + name + "\"");
    }
    for (Descriptor nested : type.getNestedTypes()) {
      renderMessage(nested, out);
    }
    for (EnumDescriptor nested : type.getEnumTypes()) {
      renderEnum(nested, out);
    }
  }

  private static String range(int first, int last) {
    return first == last ? Integer.toString(first) : first + ".." + last;
  }

  private static String type(FieldDescriptor field) {
    if (field.isMapField()) {
      Descriptor entry = field.getMessageType();
      return "map<"
          + type(entry.findFieldByName("key"))
          + ", "
          + type(entry.findFieldByName("value"))
          + ">";
    }
    switch (field.getJavaType()) {
      case MESSAGE:
        return "message " + field.getMessageType().getFullName();
      case ENUM:
        return "enum " + field.getEnumType().getFullName();
      default:
        return field.getType().name().toLowerCase(Locale.ROOT);
    }
  }

  private static String label(FieldDescriptor field) {
    if (field.isMapField()) {
      return "map";
    }
    if (field.isRepeated()) {
      return "repeated";
    }
    if (field.toProto().getProto3Optional()) {
      return "optional";
    }
    if (field.getRealContainingOneof() != null) {
      return "oneof " + field.getRealContainingOneof().getName();
    }
    return "singular";
  }

  /** {@code rpc <service>.<method>([stream ]<input>) returns ([stream ]<output>)}. */
  private static String rpc(MethodDescriptor method) {
    return "rpc "
        + method.getFullName()
        + "("
        + (method.isClientStreaming() ? "stream " : "")
        + method.getInputType().getFullName()
        + ") returns ("
        + (method.isServerStreaming() ? "stream " : "")
        + method.getOutputType().getFullName()
        + ")";
  }
}
