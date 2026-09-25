package com.pharos.compliance.transaction.repository.evidence.jdbc;

import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import java.util.Locale;
import java.util.Map;

/**
 * Small, stateless SQL-building helpers used by more than one evidence pipeline.
 *
 * <p>Every method here takes plain SQL column-reference strings (e.g. {@code "j.status"}) --
 * fixed text supplied by the calling repository, never a bound value or request-derived string,
 * exactly like the column-reference parameters used by {@code
 * TransformationFailureQueries#journeyStatsLateral}.
 */
public final class EvidenceSqlSupport {
  private EvidenceSqlSupport() {
  }

  /**
   * A fixed regex literal with no bind parameter -- {@code columnRef} is a caller-supplied column
   * reference, not user input, so inlining it into the condition text is exactly as safe as jOOQ's
   * own {@code DSL.condition("{0} ~ '^[0-9]+$'", field)} template.
   */
  public static String matchesDigitsOnly(String columnRef) {
    return columnRef + " ~ '^[0-9]+$'";
  }

  /**
   * {@code search} is the one genuinely user-supplied value here, so it only ever reaches the
   * query as a bound parameter (never concatenated into the SQL text) -- an empty search returns
   * an always-true fragment with no parameters, matching jOOQ's own {@code DSL.trueCondition()}.
   */
  public static SqlFragment searchScope(String search, String identifierColumnRef, String mtcnColumnRef) {
    if (search.isEmpty()) {
      return SqlFragment.of("true");
    }
    String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
    String sql =
        "(lower(" + identifierColumnRef + ") like :searchPattern or lower(coalesce(" + mtcnColumnRef + ", '')) like :searchPattern)";
    return SqlFragment.of(sql, Map.of("searchPattern", pattern));
  }

  /**
   * Fixed status literals, no bind parameters -- {@code statusColumnRef} is a caller-supplied
   * column reference, never user input.
   */
  public static String journeyOutcome(String statusColumnRef) {
    String upperStatus = "upper(coalesce(" + statusColumnRef + ", ''))";
    return "(case" + " when " + upperStatus + " in ('ERROR', 'FAILED', 'FAILURE') then 'ERROR'" + " when " + upperStatus
        + " in ('SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED') then 'SUCCESS'" + " when " + upperStatus
        + " = 'EXCLUDED' then 'EXCLUDED'" + " else 'PENDING'" + " end)";
  }

  /**
   * {@code (array_agg(value ORDER BY sourceRank, recordKey) FILTER (WHERE value IS NOT
   * NULL))[1]} -- picks the value from the highest-priority (lowest source-rank) row that actually
   * has a non-null value for this column, among every row merged into one (batch, identifier)
   * group. Postgres's {@code FILTER (WHERE ...)} restricts which rows feed the aggregate, so only
   * non-null values are ever aggregated; the {@code ORDER BY} then sorts survivors by priority
   * before {@code [1]} takes the first (highest-priority) one.
   */
  public static String firstNonNullByRankExpr(String columnRef, String sourceRankColumnRef, String recordKeyColumnRef) {
    return "(array_agg(" + columnRef + " order by " + sourceRankColumnRef + " asc, " + recordKeyColumnRef + " asc) filter (where "
        + columnRef + " is not null))[1]";
  }

  /**
   * The {@link #firstNonNullByRankExpr} expression, aliased back to {@code columnRef}'s own name --
   * the shape used by the merge-columns loop, where each of the 27 {@code MERGE_COLUMNS} needs
   * exactly this pattern with no other renaming.
   */
  public static String firstNonNullByRank(String columnRef, String sourceRankColumnRef, String recordKeyColumnRef) {
    return firstNonNullByRankExpr(columnRef, sourceRankColumnRef, recordKeyColumnRef) + " as " + columnRef;
  }
}
