package com.pharos.compliance.common.jooq;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;

/**
 * Safe access to fields projected by jOOQ derived tables.
 */
public final class JooqFields {
  private JooqFields() {
  }

  /**
   * Returns a projected field and fails with context when the derived table does not expose the
   * expected alias.
   */
  public static <T> Field<T> requiredField(Table<?> table, String fieldName, Class<T> fieldType) {
    Field<T> field = table.field(fieldName, fieldType);
    if (field == null) {
      throw new IllegalStateException("Required field '" + fieldName + "' is missing from derived table '" + table.getName() + "'");
    }
    return field;
  }

  public static long requiredLong(Record record, String fieldName) {
    Long value = record.get(fieldName, Long.class);
    if (value == null) {
      throw missingValue(fieldName);
    }
    return value;
  }

  public static long requiredLong(Record record, Field<Long> field) {
    Long value = record.get(field);
    if (value == null) {
      throw missingValue(field.getName());
    }
    return value;
  }

  public static int requiredInt(Record record, String fieldName) {
    Integer value = record.get(fieldName, Integer.class);
    if (value == null) {
      throw missingValue(fieldName);
    }
    return value;
  }

  public static int requiredInt(Record record, Field<Integer> field) {
    Integer value = record.get(field);
    if (value == null) {
      throw missingValue(field.getName());
    }
    return value;
  }

  public static boolean requiredBoolean(Record record, String fieldName) {
    Boolean value = record.get(fieldName, Boolean.class);
    if (value == null) {
      throw missingValue(fieldName);
    }
    return value;
  }

  private static IllegalStateException missingValue(String fieldName) {
    return new IllegalStateException("Required query result '" + fieldName + "' is null or missing");
  }
}
