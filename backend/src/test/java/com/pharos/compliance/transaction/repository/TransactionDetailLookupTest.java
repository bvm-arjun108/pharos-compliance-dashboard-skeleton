package com.pharos.compliance.transaction.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class TransactionDetailLookupTest {
  @Test
  void batchDetailUsesExactIdentityBeforeLimitWithoutSubstringSearch() {
    List<String> statements = new ArrayList<>();
    List<Object> bindings = new ArrayList<>();
    var repository = repository(statements, bindings);
    assertTrue(repository
      .findBatchEvidenceDetail(51, "BATCH-A", "12%_", "TRANSFORMED", "ALL", "ALL", "ALL", "ALL", "JOURNEY:12%_")
      .isEmpty());
    String sql = statements.getFirst();
    assertTrue(sql.contains("\"identifier\" = ?"));
    assertTrue(sql.contains("\"evidence_batch_id\" = ?"));
    assertTrue(sql.contains("\"record_key\" = ?"));
    assertTrue(sql.indexOf("exact_detail_keys") < sql.lastIndexOf("fetch next"));
    assertTrue(bindings.contains("12%_"));
    assertFalse(bindings.contains("%12%_%"));
    assertEquals(1, statements.size());
  }

  @Test
  void periodDetailRetainsOriginalBatchFilterSeparatelyFromEvidenceBatch() {
    List<String> statements = new ArrayList<>();
    List<Object> bindings = new ArrayList<>();
    var repository = repository(statements, bindings);
    assertTrue(repository
      .findPeriodEvidenceDetail(LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0), true, List.of(51, 52), false, -1,
          "BATCH-A", "123", "ALL", "EXCLUDED", "", false, "BATCH-", "JOURNEY:51:BATCH-A:123")
      .isEmpty());
    assertTrue(bindings.contains("%BATCH-%"));
    assertTrue(bindings.contains("BATCH-A"));
    assertTrue(bindings.contains("JOURNEY:51:BATCH-A:123"));
    assertFalse(bindings.contains("%123%"));
    assertTrue(statements.getFirst().contains("exact_detail_keys"));
  }

  private TransactionReportRepository repository(List<String> statements, List<Object> bindings) {
    var connection = new MockConnection(context -> {
      statements.add(context.sql());
      bindings.addAll(Arrays.asList(context.bindings()));
      var result = DSL
        .using(SQLDialect.POSTGRES)
        .newResult(DSL.field("evidence_batch_id", String.class), DSL.field("identifier", String.class),
            DSL.field("sort_ts", java.time.OffsetDateTime.class), DSL.field("record_key", String.class));
      return new MockResult[] {new MockResult(0, result)};
    });
    return new TransactionReportRepository(DSL.using(connection, SQLDialect.POSTGRES));
  }
}
