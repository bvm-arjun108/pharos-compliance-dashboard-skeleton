package com.pharos.compliance.transaction.repository;

import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.ReportGroupConfig.REPORT_GROUP_CONFIG;
import static com.pharos.compliance.jooq.tables.RuleHit.RULE_HIT;
import static com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.matchesDigitsOnly;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.TransactionSearchField;
import com.pharos.compliance.transaction.repository.projection.TransactionSearchResultProjection;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
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
 * rule_hit_exclusion_audit}'s {@code external_txn_key}, just named differently per table (the same
 * bridge {@link com.pharos.compliance.transaction.repository.evidence.RuleHitMatcher} relies on)
 * -- confirmed against real data that it's numeric in every row (0 of 52,022 journey identifiers
 * non-numeric), so a query that doesn't parse as a number matches nothing rather than falling back
 * to a raw string comparison against identifier.
 *
 * <p>Deliberately separate from {@link TransactionReportRepository}: that class's whole pipeline
 * (metric scoping, the 27-column merge, the LATERAL rule_hit rollup) exists to answer "what's the
 * current state of this batch/period," which assumes a scope to search within. This is the
 * opposite shape of query -- one specific value, no scope at all -- and doesn't need any of that
 * machinery.
 *
 * <p>Performance note: {@code record_transformation_journey} and {@code rule_hit_exclusion_audit}
 * only have indexes scoped by (rpt_grp_id, batch_id, ...) today (see ddl.sql), built for the
 * existing rule_hit-to-journey bridge that already knows its report group and batch. An unscoped
 * search like this one can't use those. {@code rule_hit.external_txn_key} already has its own
 * standalone index (txn_sur_key_rule_hit_idx), so that branch is fine as-is; the other two would
 * benefit from plain, unscoped indexes on identifier/mtcn -- added to ddl.sql but not run (see the
 * comment there), since this repo doesn't execute DDL.
 */
@Repository
@Transactional(readOnly = true)
public class TransactionSearchRepository {
  private static final String REPORT_GROUP_ID = "report_group_id";
  private static final String REPORT_GROUP_NAME = "report_group_name";
  private static final String BATCH_ID = "batch_id";
  private static final String EVIDENCE_SOURCE = "evidence_source";
  private static final String STAGE = "stage";
  private static final String STATUS = "status";
  private static final String COMMENTS = "comments";
  private static final String MATCHED_ON = "matched_on";
  private static final String OCCURRED_AT = "occurred_at";
  private static final String COUNTRY_CODE = "country_code";
  private static final String COUNTRY_NAME = "country_name";
  private static final String MTCN_LABEL = "mtcn";
  private static final String EXTERNAL_TXN_ID_LABEL = "externalTxnId";
  private static final String MTCN_COLUMN = "mtcn_value";
  private final DSLContext dsl;

  public TransactionSearchRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @SqlQueryPurpose("Search journey, exclusion-audit, and rule-hit evidence by MTCN or external transaction key")
  public List<TransactionSearchResultProjection> search(TransactionSearchField field, String query) {
    Table<?> matches;
    String matchedOnLabel;
    if (field == TransactionSearchField.MTCN) {
      matches = matchesByMtcn(query);
      matchedOnLabel = MTCN_LABEL;
    } else {
      Long externalTxnId = parseLongOrNull(query);
      if (externalTxnId == null) {
        // Not a number -- external_txn_key/identifier can never match it, so there's no query
        // worth running.
        return List.of();
      }
      matches = matchesByExternalTxnId(externalTxnId);
      matchedOnLabel = EXTERNAL_TXN_ID_LABEL;
    }
    return resolveResults(matches, matchedOnLabel);
  }

  private Table<?> matchesByMtcn(String mtcn) {
    var journeyBranch = dsl
      .select(RECORD_TRANSFORMATION_JOURNEY.RPT_GRP_ID.as(REPORT_GROUP_ID), DSL.cast(null, SQLDataType.CLOB).as(REPORT_GROUP_NAME),
          RECORD_TRANSFORMATION_JOURNEY.BATCH_ID.as(BATCH_ID), DSL.inline("JOURNEY").as(EVIDENCE_SOURCE),
          RECORD_TRANSFORMATION_JOURNEY.STAGE.as(STAGE), RECORD_TRANSFORMATION_JOURNEY.STATUS.as(STATUS),
          RECORD_TRANSFORMATION_JOURNEY.COMMENTS.as(COMMENTS),
          RECORD_TRANSFORMATION_JOURNEY.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(OCCURRED_AT),
          RECORD_TRANSFORMATION_JOURNEY.MTCN.as(MTCN_COLUMN))
      .from(RECORD_TRANSFORMATION_JOURNEY)
      .where(RECORD_TRANSFORMATION_JOURNEY.MTCN.eq(mtcn));

    var exclusionBranch = dsl
      .select(RULE_HIT_EXCLUSION_AUDIT.RPT_GRP_ID.as(REPORT_GROUP_ID), RULE_HIT_EXCLUSION_AUDIT.RPT_GRP_NAME.as(REPORT_GROUP_NAME),
          RULE_HIT_EXCLUSION_AUDIT.PROCESSING_BATCH_ID.as(BATCH_ID), DSL.inline("EXCLUSION_AUDIT").as(EVIDENCE_SOURCE),
          DSL.inline("EXCLUSION").as(STAGE), DSL.inline("EXCLUDED").as(STATUS), RULE_HIT_EXCLUSION_AUDIT.EXCLUSION_REASON_ID.as(COMMENTS),
          RULE_HIT_EXCLUSION_AUDIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(OCCURRED_AT), RULE_HIT_EXCLUSION_AUDIT.MTCN.as(MTCN_COLUMN))
      .from(RULE_HIT_EXCLUSION_AUDIT)
      .where(RULE_HIT_EXCLUSION_AUDIT.MTCN.eq(mtcn));

    var ruleHitBranch = dsl
      .select(RULE_HIT.RPT_GRP_ID.as(REPORT_GROUP_ID), RULE_HIT.RPT_GRP_NAME.as(REPORT_GROUP_NAME), RULE_HIT.EFILE_BATCH_ID.as(BATCH_ID),
          DSL.inline("RULE_HIT").as(EVIDENCE_SOURCE), DSL.inline("RULE_HIT").as(STAGE),
          DSL.when(RULE_HIT.IS_REPORTED, DSL.inline("REPORTED")).otherwise(DSL.inline("NOT_REPORTED")).as(STATUS), RULE_HIT.RULE_ID.as(
              COMMENTS), RULE_HIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(OCCURRED_AT), RULE_HIT.MTCN.as(MTCN_COLUMN))
      .from(RULE_HIT)
      .where(RULE_HIT.MTCN.eq(mtcn));

    return journeyBranch.unionAll(exclusionBranch).unionAll(ruleHitBranch).asTable("matches");
  }

  /**
   * {@code identifier}/{@code external_txn_key} are the same underlying value under different
   * names per table -- see the class Javadoc -- so the journey branch additionally guards with
   * {@link com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport#matchesDigitsOnly}
   * before casting, the same guard-then-cast pattern used everywhere else in this codebase for that
   * bridge.
   */
  private Table<?> matchesByExternalTxnId(long externalTxnId) {
    var journeyBranch = dsl
      .select(RECORD_TRANSFORMATION_JOURNEY.RPT_GRP_ID.as(REPORT_GROUP_ID), DSL.cast(null, SQLDataType.CLOB).as(REPORT_GROUP_NAME),
          RECORD_TRANSFORMATION_JOURNEY.BATCH_ID.as(BATCH_ID), DSL.inline("JOURNEY").as(EVIDENCE_SOURCE),
          RECORD_TRANSFORMATION_JOURNEY.STAGE.as(STAGE), RECORD_TRANSFORMATION_JOURNEY.STATUS.as(STATUS),
          RECORD_TRANSFORMATION_JOURNEY.COMMENTS.as(COMMENTS),
          RECORD_TRANSFORMATION_JOURNEY.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(OCCURRED_AT),
          RECORD_TRANSFORMATION_JOURNEY.MTCN.as(MTCN_COLUMN))
      .from(RECORD_TRANSFORMATION_JOURNEY)
      .where(matchesDigitsOnly(RECORD_TRANSFORMATION_JOURNEY.IDENTIFIER))
      .and(RECORD_TRANSFORMATION_JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT).eq(externalTxnId));

    var exclusionBranch = dsl
      .select(RULE_HIT_EXCLUSION_AUDIT.RPT_GRP_ID.as(REPORT_GROUP_ID), RULE_HIT_EXCLUSION_AUDIT.RPT_GRP_NAME.as(REPORT_GROUP_NAME),
          RULE_HIT_EXCLUSION_AUDIT.PROCESSING_BATCH_ID.as(BATCH_ID), DSL.inline("EXCLUSION_AUDIT").as(EVIDENCE_SOURCE),
          DSL.inline("EXCLUSION").as(STAGE), DSL.inline("EXCLUDED").as(STATUS), RULE_HIT_EXCLUSION_AUDIT.EXCLUSION_REASON_ID.as(COMMENTS),
          RULE_HIT_EXCLUSION_AUDIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(OCCURRED_AT), RULE_HIT_EXCLUSION_AUDIT.MTCN.as(MTCN_COLUMN))
      .from(RULE_HIT_EXCLUSION_AUDIT)
      .where(RULE_HIT_EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.eq(externalTxnId));

    var ruleHitBranch = dsl
      .select(RULE_HIT.RPT_GRP_ID.as(REPORT_GROUP_ID), RULE_HIT.RPT_GRP_NAME.as(REPORT_GROUP_NAME), RULE_HIT.EFILE_BATCH_ID.as(BATCH_ID),
          DSL.inline("RULE_HIT").as(EVIDENCE_SOURCE), DSL.inline("RULE_HIT").as(STAGE),
          DSL.when(RULE_HIT.IS_REPORTED, DSL.inline("REPORTED")).otherwise(DSL.inline("NOT_REPORTED")).as(STATUS), RULE_HIT.RULE_ID.as(
              COMMENTS), RULE_HIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(OCCURRED_AT), RULE_HIT.MTCN.as(MTCN_COLUMN))
      .from(RULE_HIT)
      .where(RULE_HIT.EXTERNAL_TXN_KEY.eq(externalTxnId));

    return journeyBranch.unionAll(exclusionBranch).unionAll(ruleHitBranch).asTable("matches");
  }

  /**
   * {@code matchedOnLabel} is a single literal for the whole result set, not a per-row lookup --
   * every row here matched via the one field the caller asked for, so there's nothing left to
   * infer.
   */
  private List<TransactionSearchResultProjection> resolveResults(Table<?> matches, String matchedOnLabel) {
    Field<Integer> matchReportGroupId = requiredField(matches, REPORT_GROUP_ID, Integer.class);
    Field<String> matchReportGroupName = requiredField(matches, REPORT_GROUP_NAME, String.class);
    Field<String> matchBatchId = requiredField(matches, BATCH_ID, String.class);
    Field<String> matchEvidenceSource = requiredField(matches, EVIDENCE_SOURCE, String.class);
    Field<String> matchStage = requiredField(matches, STAGE, String.class);
    Field<String> matchStatus = requiredField(matches, STATUS, String.class);
    Field<String> matchComments = requiredField(matches, COMMENTS, String.class);
    Field<String> matchOccurredAt = requiredField(matches, OCCURRED_AT, String.class);
    Field<String> matchMtcn = requiredField(matches, MTCN_COLUMN, String.class);

    var resolved = dsl
      .select(matchReportGroupId, DSL.coalesce(matchReportGroupName, reportGroupNameFor(matchReportGroupId)).as(REPORT_GROUP_NAME),
          countryCodeFor(matchReportGroupId).as(COUNTRY_CODE), countryNameFor(matchReportGroupId).as(COUNTRY_NAME), matchBatchId,
          matchEvidenceSource, matchStage, matchStatus, matchComments, DSL.inline(matchedOnLabel).as(MATCHED_ON), matchOccurredAt, matchMtcn)
      .from(matches)
      .orderBy(matchOccurredAt.desc().nullsLast());

    return resolved.fetch(r -> new TransactionSearchResultProjection(requiredInt(r, REPORT_GROUP_ID), r.get(REPORT_GROUP_NAME, String.class),
        r.get(COUNTRY_CODE, String.class), r.get(COUNTRY_NAME, String.class), r.get(BATCH_ID, String.class),
        r.get(EVIDENCE_SOURCE, String.class), r.get(STAGE, String.class), r.get(STATUS, String.class), r.get(COMMENTS, String.class),
        r.get(MATCHED_ON, String.class), r.get(OCCURRED_AT, String.class), r.get(MTCN_COLUMN, String.class)));
  }

  private static Long parseLongOrNull(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Same "latest version per report group" ordering as ReportGroupConfigRepository's
   *  latestConfigRank, applied as a correlated scalar subquery instead of a windowed rank -- the
   *  result set here is a handful of search matches, not a batch's worth of rows, so the per-row
   *  subquery cost is negligible and this avoids re-deriving the ranking CTE for a one-column
   *  lookup.
   */
  private Field<String> countryCodeFor(Field<Integer> reportGroupId) {
    return DSL.field(dsl
      .select(REPORT_GROUP_CONFIG.COUNTRY_CODE)
      .from(REPORT_GROUP_CONFIG)
      .where(REPORT_GROUP_CONFIG.RPT_GRP_ID.eq(reportGroupId))
      .orderBy(latestConfigOrder())
      .limit(1));
  }

  private Field<String> countryNameFor(Field<Integer> reportGroupId) {
    return DSL.field(dsl
      .select(REPORT_GROUP_CONFIG.COUNTRY_NAME)
      .from(REPORT_GROUP_CONFIG)
      .where(REPORT_GROUP_CONFIG.RPT_GRP_ID.eq(reportGroupId))
      .orderBy(latestConfigOrder())
      .limit(1));
  }

  /**
   * Only needed for the rule_hit_exclusion_audit/rule_hit branches when their own rpt_grp_name
   *  column happens to be null -- journey carries no report-group name at all, so it always falls
   *  back to this.
   */
  private Field<String> reportGroupNameFor(Field<Integer> reportGroupId) {
    return DSL.field(dsl
      .select(REPORT_GROUP_CONFIG.RPT_GRP_NAME)
      .from(REPORT_GROUP_CONFIG)
      .where(REPORT_GROUP_CONFIG.RPT_GRP_ID.eq(reportGroupId))
      .orderBy(latestConfigOrder())
      .limit(1));
  }

  private static List<org.jooq.OrderField<?>> latestConfigOrder() {
    return List.of(REPORT_GROUP_CONFIG.MODIFIED_TIMESTAMP.desc().nullsLast(), REPORT_GROUP_CONFIG.CREATED_TIMESTAMP.desc().nullsLast(),
        REPORT_GROUP_CONFIG.RPT_SELECTION_VERSION_ID.desc(), REPORT_GROUP_CONFIG.TRANSFORMER_VERSION_ID.desc());
  }
}
