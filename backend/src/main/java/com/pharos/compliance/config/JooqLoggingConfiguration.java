package com.pharos.compliance.config;

import com.pharos.compliance.common.jooq.logging.PrettySqlExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.boot.autoconfigure.jooq.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JooqLoggingConfiguration {
  @Bean
  DefaultConfigurationCustomizer jooqLoggingSettingsCustomizer() {
    // The application listener below produces a stable, formatted SQL block. Disable jOOQ's
    // built-in LoggerListener so every statement is logged only once.
    return configuration -> configuration.settings().withExecuteLogging(false);
  }

  @Bean
  ExecuteListenerProvider prettySqlExecuteListenerProvider() {
    return new DefaultExecuteListenerProvider(new PrettySqlExecuteListener());
  }
}
