package com.wu.compliance.dashboard.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds {@link QueryPerformanceProperties} against the real {@code application.yml} rather than a
 * hand-built one -- {@code @ConfigurationProperties(prefix = ...)} and the YAML's own nesting are
 * two independently-edited places that must agree on the same key path, and nothing else in the
 * test suite exercises that agreement: every other test constructs this record directly, bypassing
 * Spring's binder entirely. A prefix drifting out of sync between the two (e.g. the YAML's
 * top-level key being renamed without updating the annotation) does not fail at startup -- Spring's
 * {@code bindOrCreate} silently falls back to Java defaults (false for the primitive {@code
 * enabled} field) instead of raising an error, so this test is the only thing that would catch it.
 */
class QueryPerformancePropertiesBindingTest {
  @Test
  void bindsFromApplicationYamlWithLoggingEnabledByDefault() throws Exception {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    StandardEnvironment environment = new StandardEnvironment();
    sources.forEach(environment.getPropertySources()::addLast);
    // Reads the prefix straight off the class's own annotation, rather than hardcoding it here --
    // a hardcoded prefix would verify the YAML's shape but not that the annotation still agrees
    // with it, which is exactly the pairing this test exists to guard.
    String prefix = QueryPerformanceProperties.class.getAnnotation(ConfigurationProperties.class).prefix();
    BindResult<QueryPerformanceProperties> result = Binder.get(environment).bind(prefix, QueryPerformanceProperties.class);

    assertThat(result.isBound()).isTrue();
    assertThat(result.get().enabled()).isTrue();
    assertThat(result.get().slowQueryThreshold()).isEqualTo(Duration.ofMillis(250));
  }
}
