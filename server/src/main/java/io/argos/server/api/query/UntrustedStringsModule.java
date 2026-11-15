/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.openapi.ArgosUntrusted;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The JSON writer's half of 14 §2.10 (DSN-14-13, SEC-60), registered with Spring Boot's JSON mapper
 * as a module bean: every bean property annotated {@link ArgosUntrusted} that holds a {@code
 * String}, a collection or array of strings, or a map with string keys and values is written with
 * control characters (U+0000..U+001F except tab and line feed) replaced by U+FFFD and with a
 * backstop of {@value UntrustedStrings#COMMAND_BODY_MAX_BYTES} bytes per string. Nothing is escaped
 * beyond JSON itself.
 *
 * <p>The per-field limits with their {@code truncated} flag (2 KB names, 64 KB SQL) are applied by
 * the endpoint when it builds the response ({@link UntrustedStrings}); this module makes sure that
 * a forgotten call cannot put a raw control character into a response.
 */
@Component
public final class UntrustedStringsModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public UntrustedStringsModule() {
    super("argos-untrusted-strings");
    setSerializerModifier(new Modifier());
  }

  /** The cleaning applied to each string. */
  static String clean(String value) {
    return UntrustedStrings.cap(value, UntrustedStrings.COMMAND_BODY_MAX_BYTES).value();
  }

  private static final class Modifier extends ValueSerializerModifier {

    private static final long serialVersionUID = 1L;

    @Override
    public List<BeanPropertyWriter> changeProperties(
        SerializationConfig config,
        BeanDescription.Supplier description,
        List<BeanPropertyWriter> properties) {
      for (BeanPropertyWriter writer : properties) {
        if (writer.getAnnotation(ArgosUntrusted.class) == null) {
          continue;
        }
        ValueSerializer<?> serializer = serializerFor(writer.getType());
        if (serializer != null) {
          writer.assignSerializer(cast(serializer));
        }
      }
      return properties;
    }

    @SuppressWarnings("unchecked")
    private static ValueSerializer<Object> cast(ValueSerializer<?> serializer) {
      return (ValueSerializer<Object>) serializer;
    }

    private static ValueSerializer<?> serializerFor(JavaType type) {
      if (type.hasRawClass(String.class)) {
        return new CleanString();
      }
      if ((type.isCollectionLikeType() || type.isArrayType())
          && type.getContentType() != null
          && type.getContentType().hasRawClass(String.class)) {
        return new CleanStrings();
      }
      if (type.isMapLikeType()
          && type.getKeyType() != null
          && type.getKeyType().hasRawClass(String.class)
          && type.getContentType() != null
          && type.getContentType().hasRawClass(String.class)) {
        return new CleanMap();
      }
      return null;
    }
  }

  /** A string property. */
  private static final class CleanString extends StdSerializer<Object> {

    CleanString() {
      super(Object.class);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext context) {
      gen.writeString(clean((String) value));
    }
  }

  /** A collection or array of strings. */
  private static final class CleanStrings extends StdSerializer<Object> {

    CleanStrings() {
      super(Object.class);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext context) {
      gen.writeStartArray();
      if (value instanceof Collection<?> collection) {
        for (Object e : collection) {
          writeElement(e, gen);
        }
      } else if (value instanceof Object[] array) {
        for (Object e : array) {
          writeElement(e, gen);
        }
      }
      gen.writeEndArray();
    }

    private static void writeElement(Object e, JsonGenerator gen) {
      if (e == null) {
        gen.writeNull();
      } else {
        gen.writeString(clean(e.toString()));
      }
    }
  }

  /** A map of strings to strings (e.g. whitelisted request headers). */
  private static final class CleanMap extends StdSerializer<Object> {

    CleanMap() {
      super(Object.class);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext context) {
      gen.writeStartObject();
      if (value instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
          gen.writeName(clean(String.valueOf(e.getKey())));
          if (e.getValue() == null) {
            gen.writeNull();
          } else {
            gen.writeString(clean(e.getValue().toString()));
          }
        }
      }
      gen.writeEndObject();
    }
  }
}
