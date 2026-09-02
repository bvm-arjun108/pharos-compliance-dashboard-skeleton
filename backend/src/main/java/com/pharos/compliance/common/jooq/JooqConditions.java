package com.pharos.compliance.common.jooq;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Shared, PostgreSQL-specific jOOQ expressions used by the read repositories.
 */
public final class JooqConditions {
  private JooqConditions() {
  }

  /**
   * Compares a PostgreSQL {@code timestamptz} field with zone-less request bounds. Keeping this as
   * a SQL template deliberately preserves PostgreSQL's session-time-zone conversion semantics.
   */
  public static Condition zonelessTimestampBetween(Field<?> timestamp, LocalDateTime from, LocalDateTime toExclusive) {
    return DSL.condition("{0} >= {1} and {0} < {2}", timestamp, DSL.val(from), DSL.val(toExclusive));
  }

  /**
   * Case-insensitive contains search that uses a bind value instead of inlining user input.
   */
  public static Condition containsIgnoreCase(Field<String> field, String value) {
    return value == null || value.isEmpty() ? DSL.trueCondition() : DSL.lower(field).like(DSL.lower(DSL.val("%" + value + "%")));
  }

  /**
   * PostgreSQL {@code count(distinct (a, b, ...)) filter (where ...)} expression.
   */
  public static Field<Long> countDistinctTupleFiltered(Condition filter, Field<?>... columns) {
    String placeholders = IntStream
      .range(0, columns.length)
      .mapToObj(index -> "{" + index + "}")
      .collect(Collectors.joining(", "));
    Object[] arguments = new Object[columns.length + 1];
    System.arraycopy(columns, 0, arguments, 0, columns.length);
    arguments[columns.length] = filter;
    return DSL.field("count(distinct (" + placeholders + ")) filter (where {" + columns.length + "})", SQLDataType.BIGINT, arguments);
  }
}
