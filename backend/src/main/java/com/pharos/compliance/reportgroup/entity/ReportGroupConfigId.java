package com.pharos.compliance.reportgroup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ReportGroupConfigId implements Serializable {

  @Column(name = "rpt_grp_id", nullable = false)
  private Integer reportGroupId;

  @Column(name = "rpt_selection_version_id", nullable = false)
  private Integer reportSelectionVersionId;

  @Column(name = "transformer_version_id", nullable = false)
  private String transformerVersionId;

  public ReportGroupConfigId() {}

  public ReportGroupConfigId(
      Integer reportGroupId, Integer reportSelectionVersionId, String transformerVersionId) {
    this.reportGroupId = reportGroupId;
    this.reportSelectionVersionId = reportSelectionVersionId;
    this.transformerVersionId = transformerVersionId;
  }

  public Integer getReportGroupId() {
    return reportGroupId;
  }

  public Integer getReportSelectionVersionId() {
    return reportSelectionVersionId;
  }

  public String getTransformerVersionId() {
    return transformerVersionId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ReportGroupConfigId that)) {
      return false;
    }
    return Objects.equals(reportGroupId, that.reportGroupId)
        && Objects.equals(reportSelectionVersionId, that.reportSelectionVersionId)
        && Objects.equals(transformerVersionId, that.transformerVersionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reportGroupId, reportSelectionVersionId, transformerVersionId);
  }
}
