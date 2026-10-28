package com.wu.compliance.dashboard.transaction.repository.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class EvidenceColumnsTest {
  @Test
  void mergeColumnsMapToTheirJavaTypes() {
    assertThat(EvidenceColumns.mergeColumnType(EvidenceColumns.PROCESSING_COMPLETE)).isEqualTo(Boolean.class);
    assertThat(EvidenceColumns.mergeColumnType(EvidenceColumns.CURRENCY_AMOUNT)).isEqualTo(Double.class);
    assertThat(EvidenceColumns.mergeColumnType(EvidenceColumns.BUCKET_ID_COLUMN)).isEqualTo(Integer.class);
    assertThat(EvidenceColumns.mergeColumnType(EvidenceColumns.ATTEMPT_ID_COLUMN)).isEqualTo(Long.class);
    assertThat(EvidenceColumns.mergeColumnType(EvidenceColumns.RRA_KEY)).isEqualTo(Long.class);
    assertThat(EvidenceColumns.mergeColumnType(EvidenceColumns.SORT_TIMESTAMP)).isEqualTo(OffsetDateTime.class);
    assertThat(EvidenceColumns.mergeColumnType("mtcn")).isEqualTo(String.class);
    assertThat(EvidenceColumns.MERGE_COLUMNS).hasSize(27);
  }
}
