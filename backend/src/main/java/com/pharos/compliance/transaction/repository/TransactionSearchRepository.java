package com.pharos.compliance.transaction.repository;

import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.TransactionSearchField;
import com.pharos.compliance.transaction.repository.projection.TransactionSearchResultProjection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds every raw evidence row across every report group for one MTCN or external transaction
 * key, with no date range or report-group scope -- the answer to "I have this MTCN, which country
 * was it reported or excluded under?" when the caller doesn't know where to look.
 *
 * <p>{@code field} tells this class which single column to match -- previously the search matched
 * identifier, MTCN, and external transaction key all at once with no way to say which one was
 * meant, so a value that happened to collide across two different real records resolved
 * arbitrarily. {@link TransactionSearchField#EXTERNAL_TXN_ID} matches journey's own {@code
 * identifier} column too: it's the same underlying value as {@code rule_hit}/{@code
 * rule_hit_exclusion_audit}'s {@code external_txn_key}, just named differently per table --
 * confirmed against real data that it's numeric in every row, so a query that doesn't parse as a
 * number matches nothing rather than falling back to a raw string comparison against identifier.
 *
 * <p>The report-group name and country use correlated scalar lookups deliberately: the result is a
 * handful of search matches, so a ranking CTE would add more machinery without changing the
 * result.
 */
@Repository
@Transactional(readOnly = true)
public class TransactionSearchRepository {
  private static final String SEARCH_MATCHES_BY_MTCN_SQL = "sql/transaction/search-matches-by-mtcn.sql";
  private static final String SEARCH_MATCHES_BY_EXTERNAL_TXN_ID_SQL = "sql/transaction/search-matches-by-external-txn-id.sql";
  private static final String RESOLVE_SEARCH_RESULTS_SQL = "sql/transaction/resolve-search-results.sql";
  private static final String MTCN_LABEL = "mtcn";
  private static final String EXTERNAL_TXN_ID_LABEL = "externalTxnId";
  private static final RowMapper<TransactionSearchResultProjection> RESULT_ROW_MAPPER =
      (rs, rowNum) -> new TransactionSearchResultProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"),
          rs.getString("countryCode"), rs.getString("countryName"), rs.getString("batchId"), rs.getString("evidenceSource"),
          rs.getString("stage"), rs.getString("status"), rs.getString("comments"), rs.getString("matchedOn"), rs.getString("occurredAt"),
          rs.getString("mtcn"));
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;

  public TransactionSearchRepository(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  @SqlQueryPurpose("Search journey, exclusion-audit, and rule-hit evidence by MTCN or external transaction key")
  public List<TransactionSearchResultProjection> search(TransactionSearchField field, String query) {
    SqlFragment matches;
    String matchedOnLabel;
    if (field == TransactionSearchField.MTCN) {
      matches = SqlFragment.of(sql.load(SEARCH_MATCHES_BY_MTCN_SQL), Map.of("mtcn", query));
      matchedOnLabel = MTCN_LABEL;
    } else {
      Long externalTxnId = parseLongOrNull(query);
      if (externalTxnId == null) {
        // Not a number -- external_txn_key/identifier can never match it, so there's no query
        // worth running.
        return List.of();
      }
      matches = SqlFragment.of(sql.load(SEARCH_MATCHES_BY_EXTERNAL_TXN_ID_SQL), Map.of("externalTxnId", externalTxnId));
      matchedOnLabel = EXTERNAL_TXN_ID_LABEL;
    }

    SqlFragment body = SqlFragment.of(sql.load(RESOLVE_SEARCH_RESULTS_SQL), Map.of("matchedOnLabel", matchedOnLabel));
    SqlFragment combined = SqlFragment.combine(List.of(matches.asCte("matches")), body);
    return jdbc.query(combined.sql(), combined.parameterSource(), RESULT_ROW_MAPPER);
  }

  private static Long parseLongOrNull(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
