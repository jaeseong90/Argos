/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Implemented by application task classes into which the agent injected a {@code volatile
 * TraceContext argos$ctx} field (DSN-02-05, 10 §6.6 "필드 주입"). The core adds the field, this
 * interface and the two accessors while the class is first defined; classes that were already
 * loaded, JDK classes and interfaces never implement it and use the weak map of {@link
 * ContextStore} instead.
 *
 * <p>Advice never calls these methods directly: {@link ContextStore#of} returns, for task keys
 * ({@link Runnable}, {@link java.util.concurrent.Callable}, {@link
 * java.util.concurrent.ForkJoinTask}) with {@link TraceContext} values, a store that uses the field
 * when the key implements this interface and the weak map otherwise. One field serves every such
 * store: an object submitted both as a {@code Runnable} and as a {@code Callable} carries one
 * context.
 */
public interface ContextCarrier {
  /** Name of the injected field. */
  String FIELD_NAME = "argos$ctx";

  /** Name of the injected getter. */
  String GETTER_NAME = "argos$getContext";

  /** Name of the injected setter. */
  String SETTER_NAME = "argos$setContext";

  /** The context stored in the injected field, or null. */
  TraceContext argos$getContext();

  /** Stores {@code ctx} in the injected field; null clears it. */
  void argos$setContext(TraceContext ctx);
}
