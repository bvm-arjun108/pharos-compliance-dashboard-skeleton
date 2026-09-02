package com.pharos.compliance.common.jooq;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredBoolean;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class JooqFieldsTest {
  private final Table<?> derivedTable = DSL.select(DSL.inline(1).as("projected_value")).asTable("sample");

  @Test
  void returnsProjectedFieldWhenAliasExists() {
    Field<Integer> field = requiredField(derivedTable, "projected_value", Integer.class);

    assertThat(field.getName()).isEqualTo("projected_value");
  }

  @Test
  void reportsTableAndAliasWhenProjectedFieldIsMissing() {
    assertThatThrownBy(() -> requiredField(derivedTable, "missing_value", Integer.class))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Required field 'missing_value' is missing from derived table 'sample'");
  }

  @Test
  void returnsRequiredPrimitiveValuesWithoutNullableUnboxing() {
    Record record = mock(Record.class);
    when(record.get("long_value", Long.class)).thenReturn(12L);
    when(record.get("int_value", Integer.class)).thenReturn(4);
    when(record.get("boolean_value", Boolean.class)).thenReturn(true);

    assertThat(requiredLong(record, "long_value")).isEqualTo(12L);
    assertThat(requiredInt(record, "int_value")).isEqualTo(4);
    assertThat(requiredBoolean(record, "boolean_value")).isTrue();
  }

  @Test
  void reportsAliasWhenRequiredPrimitiveValueIsNull() {
    Record record = mock(Record.class);

    assertThatThrownBy(() -> requiredLong(record, "missing_count"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Required query result 'missing_count' is null or missing");
  }
}
