package com.pharos.compliance.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ReportTransformationReconciliationId implements Serializable {

  @Column(name = "rpt_grp_id", nullable = false)
  private Integer reportGroupId;

  @Column(name = "batch_id", nullable = false)
  private String batchId;

  @Column(name = "seq_no", nullable = false)
  private Integer sequenceNumber;

  public ReportTransformationReconciliationId() {}

  public ReportTransformationReconciliationId(
      Integer reportGroupId, String batchId, Integer sequenceNumber) {
    this.reportGroupId = reportGroupId;
    this.batchId = batchId;
    this.sequenceNumber = sequenceNumber;
  }

  public Integer getReportGroupId() {
    return reportGroupId;
  }

  public String getBatchId() {
    return batchId;
  }

  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ReportTransformationReconciliationId that)) {
      return false;
    }
    return Objects.equals(reportGroupId, that.reportGroupId)
        && Objects.equals(batchId, that.batchId)
        && Objects.equals(sequenceNumber, that.sequenceNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reportGroupId, batchId, sequenceNumber);
  }
}
