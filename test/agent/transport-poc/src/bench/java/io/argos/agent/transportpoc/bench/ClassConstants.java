/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** String constants ({@code CONSTANT_String}) of a class file, read from its constant pool. */
final class ClassConstants {
  private ClassConstants() {}

  static List<String> strings(byte[] classFile) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
    if (in.readInt() != 0xCAFEBABE) {
      return List.of();
    }
    in.readUnsignedShort();
    in.readUnsignedShort();
    int count = in.readUnsignedShort();
    String[] utf8 = new String[count];
    List<Integer> stringRefs = new ArrayList<>();
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 8 -> stringRefs.add(in.readUnsignedShort());
        case 7, 16, 19, 20 -> in.readUnsignedShort();
        case 15 -> {
          in.readUnsignedByte();
          in.readUnsignedShort();
        }
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
        case 5, 6 -> {
          in.readLong();
          i++;
        }
        default -> throw new IOException("unknown constant pool tag " + tag);
      }
    }
    List<String> result = new ArrayList<>();
    for (int ref : stringRefs) {
      if (ref > 0 && ref < count && utf8[ref] != null) {
        result.add(utf8[ref]);
      }
    }
    return result;
  }
}
