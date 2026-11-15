/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.ContextDataFactory;
import org.apache.logging.log4j.core.impl.DefaultLogEventFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.impl.LogEventFactory;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.StringMap;

/**
 * Creates every Log4j event of the server with its secrets masked ({@link LogMasking}), before any
 * appender or layout sees it. Installed by {@code log4j2.component.properties} ({@code
 * log4j2.logEventFactory}), so it applies to every logger of the JVM, including Spring, Tomcat,
 * HikariCP and the JDBC drivers, and needs no Log4j plugin.
 *
 * <ul>
 *   <li>The message is formatted and masked; an unchanged message keeps its original object.
 *   <li>An exception is rendered once ({@link Throwable#printStackTrace(PrintWriter)}, causes
 *       included), masked and stored in the context data as {@value #ERROR_CLASS} and {@value
 *       #ERROR_STACK}; the event itself then carries no {@link Throwable}, so no layout can print
 *       an unmasked stack trace. The JSON template writes these two keys as {@code error.class} and
 *       {@code error.stack}.
 * </ul>
 */
public class MaskingLogEventFactory implements LogEventFactory {

  /** Context data key of the exception class name. */
  public static final String ERROR_CLASS = "argos.error.class";

  /** Context data key of the masked stack trace. */
  public static final String ERROR_STACK = "argos.error.stack";

  /** Upper bound of a rendered stack trace (characters); longer traces are cut. */
  static final int MAX_STACK_CHARS = 32 * 1024;

  private final DefaultLogEventFactory delegate = new DefaultLogEventFactory();

  @Override
  public LogEvent createEvent(
      String loggerName,
      Marker marker,
      String fqcn,
      Level level,
      Message data,
      List<Property> properties,
      Throwable t) {
    return mask(delegate.createEvent(loggerName, marker, fqcn, level, data, properties, t));
  }

  @Override
  public LogEvent createEvent(
      String loggerName,
      Marker marker,
      String fqcn,
      StackTraceElement location,
      Level level,
      Message data,
      List<Property> properties,
      Throwable t) {
    return mask(
        delegate.createEvent(loggerName, marker, fqcn, location, level, data, properties, t));
  }

  /** {@code event} with a masked message and, for an exception, the masked stack trace. */
  static LogEvent mask(LogEvent event) {
    Message message = event.getMessage();
    String formatted = message == null ? null : message.getFormattedMessage();
    String masked = LogMasking.mask(formatted);
    Throwable thrown = event.getThrown();
    boolean messageChanged = masked != null && !masked.equals(formatted);
    if (!messageChanged && thrown == null) {
      return event;
    }
    Log4jLogEvent.Builder builder = new Log4jLogEvent.Builder(event);
    if (messageChanged) {
      builder.setMessage(new SimpleMessage(masked));
    }
    if (thrown != null) {
      StringMap context = ContextDataFactory.createContextData(event.getContextData());
      context.putValue(ERROR_CLASS, thrown.getClass().getName());
      context.putValue(ERROR_STACK, LogMasking.mask(render(thrown)));
      // A fresh event has no thrown proxy yet, so dropping the throwable drops every trace of it.
      builder.setContextData(context).setThrown(null);
    }
    return builder.build();
  }

  /** The stack trace as {@code printStackTrace} prints it, with line feeds, bounded. */
  static String render(Throwable thrown) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      thrown.printStackTrace(writer);
    }
    String text = out.toString().replace("\r\n", "\n").stripTrailing();
    return text.length() <= MAX_STACK_CHARS
        ? text
        : text.substring(0, MAX_STACK_CHARS) + "\n\t... (stack trace cut)";
  }
}
