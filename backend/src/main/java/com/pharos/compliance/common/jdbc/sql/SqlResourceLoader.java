package com.pharos.compliance.common.jdbc.sql;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Loads a repository's hand-written SQL from {@code src/main/resources/sql/<feature>/<name>.sql}
 * and caches it in memory, so the file is read from disk once per query, not once per request.
 * Externalizing SQL to plain {@code .sql} files (rather than Java text blocks) keeps large queries
 * -- LATERAL joins, {@code json_agg(json_build_object(...))}, tuple-IN predicates -- pasteable
 * directly into a Postgres client for {@code EXPLAIN}, which matters for a migration whose bar is
 * "100% identical resultset."
 */
@Component
public class SqlResourceLoader {
  private final ResourceLoader resourceLoader;
  private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

  public SqlResourceLoader(ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  /**
   * @param classpathLocation path under the classpath root, e.g. {@code "sql/health/select-one.sql"}
   */
  public String load(String classpathLocation) {
    return cache.computeIfAbsent(classpathLocation, this::readResource);
  }

  private String readResource(String location) {
    Resource resource = resourceLoader.getResource("classpath:" + location);
    try (InputStream in = resource.getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load SQL resource: " + location, e);
    }
  }
}
