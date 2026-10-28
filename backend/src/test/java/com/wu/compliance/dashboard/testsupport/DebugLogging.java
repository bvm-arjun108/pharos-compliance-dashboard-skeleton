package com.wu.compliance.dashboard.testsupport;

import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Lets a test exercise the {@code isDebugEnabled()} branches of the application's own loggers, then
 * puts the previous levels back so other tests still see the default configuration. A running
 * Spring context may have pinned a more specific logger (e.g. the SQL tracing logger is set OFF by
 * {@code application.yml}), so every configured logger under the application package is switched,
 * not just the package root.
 */
public final class DebugLogging implements AutoCloseable {
  private static final String ROOT = "com.wu.compliance.dashboard";
  private final Map<String, Level> previous = new HashMap<>();

  private DebugLogging() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    previous.put(ROOT, LogManager.getLogger(ROOT).getLevel());
    context
      .getConfiguration()
      .getLoggers()
      .keySet()
      .stream()
      .filter(name -> name.startsWith(ROOT))
      .forEach(name -> previous.put(name, context.getConfiguration().getLoggerConfig(name).getLevel()));
    previous
      .keySet()
      .forEach(name -> Configurator.setLevel(name, Level.DEBUG));
    // Loggers resolved lazily by class pick up the root change; the explicit ones above cover pinned ones.
  }

  public static DebugLogging enable() {
    return new DebugLogging();
  }

  @Override
  public void close() {
    previous.forEach((name, level) -> Configurator.setLevel(name, level));
  }
}
