package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IDENTIFIER_BIGINT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MATCHED_IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_HIT_MATCHES;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_HIT_TABLE;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Resolves {@code rule_hit} rows back to the journey identifier they belong to -- the join-based
 * replacement for the correlated-scalar-subquery version this session found and fixed for being
 * 100-600x slower. Shared by {@link BatchEvidenceQueries} and {@link PeriodEvidenceQueries}, each
 * of which supplies its own already-scoped {@code journeyScoped} lookup table (one batch, or one
 * period's batches) and {@code rule_hit} scope condition.
 */
public class RuleHitMatcher {
  private final DSLContext dsl;

  public RuleHitMatcher(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Resolves each {@code rule_hit} row in {@code ruleHitScope} back to the journey identifier it
   * belongs to, joining against {@code journeyScoped} -- the (already small: one batch, or one
   * period's batches) set of journey rows the caller actually cares about -- instead of running
   * {@code byIdentifier}/{@code byMtcn} as correlated scalar subqueries evaluated once per
   * {@code rule_hit} row across the whole report group. That correlated form measured 100-600x
   * slower than every other metric on the exact same batch (1.2-1.4s vs 2-17ms locally, against a
   * report group with only 3,591 rule_hit rows -- a real production report group's rule_hit table
   * is likely orders of magnitude larger). Joining lets Postgres use a real join algorithm
   * (hash/merge) against a tiny lookup table instead of an O(rule_hit rows in the report group)
   * nested loop.
   *
   * <p>{@code GROUP BY} in the two lookup tables guarantees at most one row per join key, matching
   * the original correlated subqueries' implicit "arbitrary pick" (neither had an {@code ORDER BY}
   * before their {@code LIMIT 1}) with a deterministic {@code MIN()} tie-break instead. Confirmed
   * against real mock data that journey identifiers are unique within a batch (0 duplicates across
   * 3,069 batches) -- so the identifier-match path is always exact -- and that duplicate mtcns
   * within one batch are exceedingly rare (2 of 3,069 batches), so this only changes which
   * otherwise-arbitrary row wins in an already-rare edge case, never whether a match is found.
   */
  public Table<?> ruleHitMatches(Condition ruleHitScope, Table<?> journeyScoped) {
    Field<String> jIdentifier = requiredField(journeyScoped, IDENTIFIER, String.class);
    Field<String> jMtcn = requiredField(journeyScoped, "mtcn", String.class);
    Field<Long> jIdentifierBigint = requiredField(journeyScoped, IDENTIFIER_BIGINT, Long.class);

    var byIdentifierLookup = dsl
      .select(jIdentifierBigint, DSL.min(jIdentifier).as(IDENTIFIER))
      .from(journeyScoped)
      .where(jIdentifierBigint.isNotNull())
      .groupBy(jIdentifierBigint)
      .asTable("by_identifier_lookup");
    var byMtcnLookup = dsl
      .select(jMtcn, DSL.min(jIdentifier).as(IDENTIFIER))
      .from(journeyScoped)
      .where(jMtcn.isNotNull())
      .groupBy(jMtcn)
      .asTable("by_mtcn_lookup");

    Field<Long> lIdentifierBigint = requiredField(byIdentifierLookup, IDENTIFIER_BIGINT, Long.class);
    Field<String> lByIdentifier = requiredField(byIdentifierLookup, IDENTIFIER, String.class);
    Field<String> lMtcn = requiredField(byMtcnLookup, "mtcn", String.class);
    Field<String> lByMtcn = requiredField(byMtcnLookup, IDENTIFIER, String.class);

    return dsl
      .select(RULE_HIT_TABLE.fields())
      .select(DSL.coalesce(lByIdentifier, lByMtcn).as(MATCHED_IDENTIFIER))
      .from(RULE_HIT_TABLE)
      .leftJoin(byIdentifierLookup)
      .on(lIdentifierBigint.eq(RULE_HIT_TABLE.EXTERNAL_TXN_KEY))
      .leftJoin(byMtcnLookup)
      .on(lMtcn.eq(RULE_HIT_TABLE.MTCN))
      .where(ruleHitScope)
      .asTable(RULE_HIT_MATCHES);
  }

  /**
   * Enrichment lookup keyed by report group, batch and transaction; preserves identifier-first fallback.
   */
  public Table<?> scopedRuleHitMatches(Condition ruleHitScope, Table<?> journeyScoped) {
    Field<Integer> group = requiredField(journeyScoped, "rpt_grp_id", Integer.class);
    Field<String> batch = requiredField(journeyScoped, "batch_id", String.class);
    Field<String> jIdentifier = requiredField(journeyScoped, IDENTIFIER, String.class);
    Field<String> jMtcn = requiredField(journeyScoped, "mtcn", String.class);
    Field<Long> jIdentifierBigint = requiredField(journeyScoped, IDENTIFIER_BIGINT, Long.class);

    var byIdentifierLookup = dsl
      .select(group, batch, jIdentifierBigint, DSL.min(jIdentifier).as(IDENTIFIER))
      .from(journeyScoped)
      .where(jIdentifierBigint.isNotNull())
      .groupBy(group, batch, jIdentifierBigint)
      .asTable("by_identifier_lookup");
    var byMtcnLookup = dsl
      .select(group, batch, jMtcn, DSL.min(jIdentifier).as(IDENTIFIER))
      .from(journeyScoped)
      .where(jMtcn.isNotNull())
      .groupBy(group, batch, jMtcn)
      .asTable("by_mtcn_lookup");

    Field<Long> lIdentifierBigint = requiredField(byIdentifierLookup, IDENTIFIER_BIGINT, Long.class);
    Field<String> lByIdentifier = requiredField(byIdentifierLookup, IDENTIFIER, String.class);
    Field<String> lMtcn = requiredField(byMtcnLookup, "mtcn", String.class);
    Field<String> lByMtcn = requiredField(byMtcnLookup, IDENTIFIER, String.class);

    return dsl
      .select(RULE_HIT_TABLE.fields())
      .select(DSL.coalesce(lByIdentifier, lByMtcn).as(MATCHED_IDENTIFIER))
      .from(RULE_HIT_TABLE)
      .leftJoin(byIdentifierLookup)
      .on(lIdentifierBigint.eq(RULE_HIT_TABLE.EXTERNAL_TXN_KEY))
      .and(requiredField(byIdentifierLookup, "rpt_grp_id", Integer.class).eq(RULE_HIT_TABLE.RPT_GRP_ID))
      .and(requiredField(byIdentifierLookup, "batch_id", String.class).eq(RULE_HIT_TABLE.EFILE_BATCH_ID))
      .leftJoin(byMtcnLookup)
      .on(lMtcn.eq(RULE_HIT_TABLE.MTCN))
      .and(requiredField(byMtcnLookup, "rpt_grp_id", Integer.class).eq(RULE_HIT_TABLE.RPT_GRP_ID))
      .and(requiredField(byMtcnLookup, "batch_id", String.class).eq(RULE_HIT_TABLE.EFILE_BATCH_ID))
      .where(ruleHitScope)
      .asTable(RULE_HIT_MATCHES);
  }
}
