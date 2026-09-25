package com.pharos.compliance.common.jdbc.sql;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class SqlResourceLoaderTest {
  @Test
  void loadsSqlTextFromTheClasspath() {
    SqlResourceLoader loader = new SqlResourceLoader(new DefaultResourceLoader());
    String sql = loader.load("sql/health/select-one.sql");
    assertTrue(sql.contains("SELECT 1 AS one"), "should read the real resource file, but got: " + sql);
  }

  @Test
  void cachesAfterTheFirstReadInsteadOfHittingDiskEveryCall() {
    AtomicInteger resourceLookups = new AtomicInteger();
    var countingLoader = new DefaultResourceLoader() {
      @Override
      public Resource getResource(String location) {
        resourceLookups.incrementAndGet();
        return super.getResource(location);
      }
    };
    SqlResourceLoader loader = new SqlResourceLoader(countingLoader);

    loader.load("sql/health/select-one.sql");
    loader.load("sql/health/select-one.sql");
    loader.load("sql/health/select-one.sql");

    assertTrue(resourceLookups.get() == 1,
        "resource should be read from disk once and cached, but was read " + resourceLookups.get() + " times");
  }

  @Test
  void missingResourceFailsFastWithAClearException() {
    SqlResourceLoader loader = new SqlResourceLoader(new DefaultResourceLoader());
    assertThrows(UncheckedIOException.class, () -> loader.load("sql/does-not-exist.sql"));
  }
}
