/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Makes the <b>response schemas</b> of the record DTOs say what the JSON writer does (14 §2.5,
 * §2.9, §9 contract rows; O-R03): which properties are always present ({@code required}) and which
 * may be {@code null} (OpenAPI 3.1 {@code type: [T, "null"]}, or {@code anyOf} with {@code {type:
 * null}} for a {@code $ref}). Without it oasdiff does not see the removal of a property (an
 * optional response property may disappear) and a client generator types every field as never null.
 *
 * <p><b>Response schemas only.</b> A model converter cannot tell whether a record is read or
 * written, so every record schema leaves this converter in its response form, and the springdoc
 * description it started from (the {@code required} list of explicit {@code @Schema(requiredMode =
 * REQUIRED)} only, the property schemas without an added {@code null}) is kept on the schema as
 * {@link #REQUEST_FORM}. {@link ArgosRequestSchemaCustomizer} then puts that form back on every
 * component reachable from a request body (a request body field the writer would always write is
 * still optional for the client), fails the document when a component is reachable from both a
 * request body and a 2xx response, and removes {@link #REQUEST_FORM} from every schema: the
 * extension never reaches the document.
 *
 * <p>Rules, per record component, from the Jackson annotations the writer obeys (component, then
 * record, then the writer's default {@code ALWAYS}; the server sets no other default):
 *
 * <ul>
 *   <li><b>required</b>: the inclusion is {@code ALWAYS}, or the component is a primitive (never
 *       null, so {@code NON_NULL}, {@code NON_ABSENT} and {@code NON_EMPTY} never drop it), or it
 *       carries {@code @Schema(requiredMode = REQUIRED)}; {@code @Schema(requiredMode =
 *       NOT_REQUIRED)} and {@code @JsonIgnore} exclude it;
 *   <li><b>nullable</b>: a non-primitive component written with {@code ALWAYS} whose type is marked
 *       {@link Nullable @Nullable} or is an {@code Optional} (empty is written as {@code null}), or
 *       an {@code Optional} written with {@code NON_NULL} (a null reference is left out, empty is
 *       {@code null}: {@code meta.next_cursor});
 *   <li><b>nullable elements</b>: {@code List<@Nullable T>}, arrays of {@code @Nullable T} and
 *       {@code Map<K, @Nullable V>}, nested at any depth (metric values with gaps).
 * </ul>
 *
 * A record with a {@code @JsonUnwrapped} component (its JSON properties are another type's) is left
 * as springdoc describes it. Added by WO-23 (review findings api-contract-security-1 and r1-new-1).
 */
@Component
public class ArgosRecordSchemaConverter implements ModelConverter {

  /**
   * The schema extension that holds the {@link RequestForm} of a record schema between this
   * converter and {@link ArgosRequestSchemaCustomizer}, which removes it from every schema.
   */
  public static final String REQUEST_FORM = "x-argos-internal-request-form";

  /**
   * The springdoc description of a record schema before this converter changed it: what a request
   * body schema says.
   *
   * @param required springdoc's {@code required} list (explicit {@code @Schema(requiredMode =
   *     REQUIRED)} only), null when it had none
   * @param properties the original schemas of the properties this converter replaced, by JSON name
   */
  @SuppressWarnings("rawtypes") // swagger's raw Schema type
  public record RequestForm(@Nullable List<String> required, Map<String, Schema> properties) {

    public RequestForm {
      required = required == null ? null : List.copyOf(required);
      properties = Map.copyOf(properties);
    }
  }

  private static final String COMPONENTS_PREFIX = "#/components/schemas/";

  @Override
  @SuppressWarnings("rawtypes") // swagger's interface uses the raw Schema type
  public Schema resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    if (!chain.hasNext()) {
      return null;
    }
    Schema resolved = chain.next().resolve(type, context, chain);
    if (resolved == null || type == null) {
      return resolved;
    }
    Class<?> raw = rawClass(type.getType());
    if (raw == null || !raw.isRecord()) {
      return resolved;
    }
    Schema model = resolved;
    String ref = resolved.get$ref();
    if (ref != null && ref.startsWith(COMPONENTS_PREFIX) && context.getDefinedModels() != null) {
      model = context.getDefinedModels().get(ref.substring(COMPONENTS_PREFIX.length()));
    }
    if (model != null) {
      apply(raw, model);
    }
    return resolved;
  }

  /**
   * Sets {@code required} and the nullable types of {@code model}, the schema of {@code record}
   * (its response form), and keeps springdoc's description as {@link #REQUEST_FORM} the first time
   * (a model that already carries one has been through here). The original property schemas are not
   * changed: a property that gets a nullable type is a copy.
   */
  // swagger's raw Schema maps; a copy is checked by identity (swagger returns the input on failure)
  @SuppressWarnings({"rawtypes", "unchecked", "ReferenceEquality"})
  static void apply(Class<?> record, Schema model) {
    Map<String, Schema> properties = model.getProperties();
    if (properties == null || properties.isEmpty()) {
      return;
    }
    RecordComponent[] components = record.getRecordComponents();
    if (components == null) {
      return;
    }
    for (RecordComponent component : components) {
      if (annotation(component, field(record, component), JsonUnwrapped.class) != null) {
        return; // its properties are the unwrapped value's: leave the record as described
      }
    }
    boolean firstPass =
        model.getExtensions() == null || !model.getExtensions().containsKey(REQUEST_FORM);
    List<String> originalRequired =
        model.getRequired() == null ? null : new ArrayList<>(model.getRequired());
    Map<String, Schema> originals = new LinkedHashMap<>();
    JsonInclude.Include recordInclusion = inclusion(record.getAnnotation(JsonInclude.class));
    Set<String> required = new TreeSet<>();
    if (model.getRequired() != null) {
      required.addAll(model.getRequired());
    }
    Map<String, Schema> updated = new LinkedHashMap<>(properties);
    for (RecordComponent component : components) {
      Field field = field(record, component);
      JsonIgnore ignore = annotation(component, field, JsonIgnore.class);
      if (ignore != null && ignore.value()) {
        continue;
      }
      String name = propertyName(component, field);
      Schema property = updated.get(name);
      if (property == null) {
        continue;
      }
      JsonInclude.Include inclusion =
          inclusion(annotation(component, field, JsonInclude.class), recordInclusion);
      boolean primitive = component.getType().isPrimitive();
      boolean optional = isOptional(component.getType());
      io.swagger.v3.oas.annotations.media.Schema doc =
          annotation(component, field, io.swagger.v3.oas.annotations.media.Schema.class);
      io.swagger.v3.oas.annotations.media.Schema.RequiredMode mode =
          doc == null
              ? io.swagger.v3.oas.annotations.media.Schema.RequiredMode.AUTO
              : doc.requiredMode();
      boolean present =
          inclusion == JsonInclude.Include.ALWAYS
              || (primitive
                  && (inclusion == JsonInclude.Include.NON_NULL
                      || inclusion == JsonInclude.Include.NON_ABSENT
                      || inclusion == JsonInclude.Include.NON_EMPTY));
      if (mode == io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED) {
        required.remove(name);
      } else if (present
          || mode == io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) {
        required.add(name);
      }
      boolean nullable =
          !primitive
              && ((inclusion == JsonInclude.Include.ALWAYS
                      && (optional
                          || component.getAnnotatedType().isAnnotationPresent(Nullable.class)))
                  || (inclusion == JsonInclude.Include.NON_NULL && optional));
      if (nullable || hasNullableElements(component.getAnnotatedType())) {
        // elements() and nullable() change the schema they get: give them a copy and keep the
        // original for the request form.
        originals.put(name, property);
        Schema copy =
            AnnotationsUtils.clone(property, property.getSpecVersion() == SpecVersion.V31);
        if (copy == property) {
          // swagger returns the schema itself when it cannot copy it
          throw new IllegalStateException(
              "Cannot copy the schema of " + record.getSimpleName() + "." + name);
        }
        Schema described = elements(component.getAnnotatedType(), copy);
        if (nullable) {
          described = nullable(described);
        }
        updated.put(name, described);
      }
    }
    if (firstPass) {
      model.addExtension(REQUEST_FORM, new RequestForm(originalRequired, originals));
    }
    model.setProperties(updated);
    model.setRequired(required.isEmpty() ? null : new ArrayList<>(required));
  }

  /**
   * Whether {@link #elements} would make an element nullable: a {@code @Nullable} type argument or
   * array component, at any depth.
   */
  private static boolean hasNullableElements(java.lang.reflect.AnnotatedType type) {
    java.lang.reflect.AnnotatedType[] nested;
    if (type instanceof AnnotatedParameterizedType parameterized) {
      nested = parameterized.getAnnotatedActualTypeArguments();
    } else if (type instanceof AnnotatedArrayType array) {
      nested = new java.lang.reflect.AnnotatedType[] {array.getAnnotatedGenericComponentType()};
    } else {
      return false;
    }
    for (java.lang.reflect.AnnotatedType argument : nested) {
      if (argument.isAnnotationPresent(Nullable.class) || hasNullableElements(argument)) {
        return true;
      }
    }
    return false;
  }

  /** {@code schema} with the elements of arrays and maps made nullable where the type says so. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Schema elements(java.lang.reflect.AnnotatedType type, Schema schema) {
    if (schema == null) {
      return null;
    }
    if (type instanceof AnnotatedParameterizedType parameterized
        && parameterized.getType() instanceof ParameterizedType p
        && p.getRawType() instanceof Class<?> raw) {
      java.lang.reflect.AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
      if (Collection.class.isAssignableFrom(raw) && arguments.length == 1) {
        Schema items = schema.getItems();
        if (items != null) {
          schema.setItems(element(arguments[0], items));
        }
      } else if (Map.class.isAssignableFrom(raw) && arguments.length == 2) {
        if (schema.getAdditionalProperties() instanceof Schema values) {
          schema.setAdditionalProperties(element(arguments[1], values));
        }
      } else if (isOptional(raw) && arguments.length == 1) {
        return elements(arguments[0], schema);
      }
    } else if (type instanceof AnnotatedArrayType array && schema.getItems() != null) {
      schema.setItems(element(array.getAnnotatedGenericComponentType(), schema.getItems()));
    }
    return schema;
  }

  @SuppressWarnings("rawtypes")
  private static Schema element(java.lang.reflect.AnnotatedType type, Schema schema) {
    Schema element = elements(type, schema);
    return type.isAnnotationPresent(Nullable.class) ? nullable(element) : element;
  }

  /** {@code schema} that also admits {@code null} (idempotent). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static Schema nullable(Schema schema) {
    if (schema == null) {
      return null;
    }
    if (schema.get$ref() != null) {
      Schema wrapper = new Schema();
      wrapper.setDescription(schema.getDescription());
      if (schema.getExtensions() != null) {
        schema.getExtensions().forEach((k, v) -> wrapper.addExtension((String) k, v));
      }
      wrapper.addAnyOfItem(new Schema().$ref(schema.get$ref()));
      wrapper.addAnyOfItem(nullType());
      return wrapper;
    }
    Set<String> types = schema.getTypes();
    if (types == null || types.isEmpty()) {
      if (schema.getType() == null) {
        if (schema.getAnyOf() != null
            && schema.getAnyOf().stream().noneMatch(s -> isNull((Schema) s))) {
          schema.addAnyOfItem(nullType());
        }
        return schema; // no type: null is already allowed
      }
      types = new LinkedHashSet<>(List.of(schema.getType()));
    } else {
      types = new LinkedHashSet<>(types);
    }
    types.add("null");
    schema.setTypes(types);
    if (schema.getEnum() != null && !schema.getEnum().contains(null)) {
      List<Object> values = new ArrayList<>(schema.getEnum());
      values.add(null);
      schema.setEnum(values);
    }
    return schema;
  }

  @SuppressWarnings("rawtypes")
  private static Schema nullType() {
    Schema none = new Schema();
    none.addType("null");
    return none;
  }

  @SuppressWarnings("rawtypes")
  private static boolean isNull(Schema schema) {
    return schema.getTypes() != null
        && schema.getTypes().size() == 1
        && schema.getTypes().contains("null");
  }

  private static boolean isOptional(Class<?> type) {
    return type == Optional.class
        || type == OptionalInt.class
        || type == OptionalLong.class
        || type == OptionalDouble.class;
  }

  /** The JSON name: {@code @JsonProperty} on the component (field or accessor), else its name. */
  private static String propertyName(RecordComponent component, Field field) {
    JsonProperty property = annotation(component, field, JsonProperty.class);
    return property != null && !property.value().isEmpty() ? property.value() : component.getName();
  }

  /**
   * A Jackson or swagger annotation of a record component. Their targets do not include record
   * components, so the compiler puts them on the field, the accessor and the constructor parameter.
   */
  private static <A extends java.lang.annotation.Annotation> A annotation(
      RecordComponent component, Field field, Class<A> type) {
    for (AnnotatedElement element :
        new AnnotatedElement[] {component, field, component.getAccessor()}) {
      if (element != null) {
        A a = element.getAnnotation(type);
        if (a != null) {
          return a;
        }
      }
    }
    return null;
  }

  private static Field field(Class<?> record, RecordComponent component) {
    try {
      return record.getDeclaredField(component.getName());
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  private static JsonInclude.Include inclusion(JsonInclude annotation) {
    if (annotation == null || annotation.value() == JsonInclude.Include.USE_DEFAULTS) {
      return null;
    }
    return annotation.value();
  }

  private static JsonInclude.Include inclusion(
      JsonInclude annotation, JsonInclude.Include recordInclusion) {
    JsonInclude.Include own = inclusion(annotation);
    if (own != null) {
      return own;
    }
    return recordInclusion != null ? recordInclusion : JsonInclude.Include.ALWAYS;
  }

  /** The class of a swagger type: a class, a parameterized type or a Jackson 2 type. */
  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> c) {
      return c;
    }
    if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
      return c;
    }
    if (type instanceof JavaType j) {
      return j.getRawClass();
    }
    return null;
  }
}
