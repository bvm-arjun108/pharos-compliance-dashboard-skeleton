package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_ERROR;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_PENDING;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_SUCCESS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_REPORTED;
import java.util.Locale;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Small, stateless SQL-building helpers used by more than one evidence pipeline in this package.
 */
public final class EvidenceSqlSupport {
  private EvidenceSqlSupport() {
  }

  public static Condition matchesDigitsOnly(Field<String> field) {
    return DSL.condition("{0} ~ '^[0-9]+$'", field);
  }

  public static Condition searchScope(String search, Field<String> identifier, Field<String> mtcn) {
    if (search.isEmpty()) {
      return DSL.trueCondition();
    }
    String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
    return DSL.lower(identifier).like(pattern).or(DSL.lower(DSL.coalesce(mtcn, "")).like(pattern));
  }

  public static Field<String> journeyOutcome(Field<String> status) {
    Field<String> upperStatus = DSL.upper(DSL.coalesce(status, ""));
    return DSL
      .when(upperStatus.in(OUTCOME_ERROR, "FAILED", "FAILURE"), DSL.inline(OUTCOME_ERROR))
      .when(upperStatus.in(OUTCOME_SUCCESS, "COMPLETED", "TRANSFORMED", VALUE_REPORTED), DSL.inline(OUTCOME_SUCCESS))
      .when(upperStatus.eq(VALUE_EXCLUDED), DSL.inline(VALUE_EXCLUDED))
      .otherwise(DSL.inline(OUTCOME_PENDING));
  }

  /**
   * {@code (ARRAY_AGG(value ORDER BY rank, key) FILTER (WHERE value IS NOT NULL))[1]} -- picks the
   * value from the highest-priority (lowest source_rank) row that actually has a non-null value for
   * this column, among every row merged into one (batch, identifier) group. No jOOQ DSL builds an
   * array-index expression, so the aggregate is built with the real fluent API
   * (arrayAgg/orderBy/filterWhere) and only the trailing {@code [1]} is a raw template.
   */
  public static <T> Field<T> firstNonNullByRank(Field<T> value, Field<Integer> sourceRank, Field<String> recordKey, Class<T> type) {
    Field<T[]> aggregated = DSL.arrayAgg(value).orderBy(sourceRank.asc(), recordKey.asc()).filterWhere(value.isNotNull());
    Class<T[]> arrayType = aggregated.getType();
    return DSL.field("({0})[1]", type, DSL.field("{0}", arrayType, aggregated));
  }

  public static <T> Field<T> firstNonNullByRank(Table<?> ranked, String column, Class<T> type, Field<Integer> sourceRank,
      Field<String> recordKey) {
    Field<T> value = requiredField(ranked, column, type);
    return firstNonNullByRank(value, sourceRank, recordKey, type).as(column);
  }
}
