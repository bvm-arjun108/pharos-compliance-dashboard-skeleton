package com.pharos.compliance.common.jooq.logging;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SqlQueryPurposeResolverTest {
  private static final String TEST_PURPOSE = "Load test reconciliation evidence";

  @Test
  void resolvesPurposeFromAnnotatedCallingMethod() {
    assertThat(resolveFromAnnotatedMethod()).isEqualTo(TEST_PURPOSE);
  }

  @Test
  void providesClearFallbackOutsideAnnotatedRepositoryMethod() {
    assertThat(SqlQueryPurposeResolver.resolve()).isEqualTo(SqlQueryPurposeResolver.UNCLASSIFIED_QUERY);
  }

  @SqlQueryPurpose(TEST_PURPOSE)
  private String resolveFromAnnotatedMethod() {
    return SqlQueryPurposeResolver.resolve();
  }
}
