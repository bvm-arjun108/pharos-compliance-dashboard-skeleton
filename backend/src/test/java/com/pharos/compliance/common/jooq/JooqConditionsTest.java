package com.pharos.compliance.common.jooq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class JooqConditionsTest {
  @Test
  void containsSearchKeepsUserInputAsABindValue() {
    var query =
        DSL
      .using(SQLDialect.POSTGRES)
      .selectOne()
      .where(JooqConditions.containsIgnoreCase(DSL.field("batch_id", String.class), "Batch-42"));

    assertFalse(query.getSQL().contains("Batch-42"));
    assertEquals(List.of("%Batch-42%"), query.getBindValues());
  }

  @Test
  void emptyContainsSearchDoesNotAddABindValue() {
    var query = DSL
      .using(SQLDialect.POSTGRES)
      .selectOne()
      .where(JooqConditions.containsIgnoreCase(DSL.field("batch_id", String.class), ""));

    assertTrue(query.getBindValues().isEmpty());
  }

  @Test
  void zonelessTimestampBoundsRemainBoundForPostgresConversion() {
    LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
    LocalDateTime toExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);
    var query = DSL
      .using(SQLDialect.POSTGRES)
      .selectOne()
      .where(JooqConditions.zonelessTimestampBetween(DSL.field("created_timestamp"), from, toExclusive));

    assertEquals(List.of(from, toExclusive), query.getBindValues());
  }
}
