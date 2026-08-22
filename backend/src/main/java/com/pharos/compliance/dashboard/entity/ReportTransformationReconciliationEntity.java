package com.pharos.compliance.dashboard.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "report_transformation_reconciliation", schema = "pharos")
public class ReportTransformationReconciliationEntity {

  @EmbeddedId private ReportTransformationReconciliationId id;

  protected ReportTransformationReconciliationEntity() {}

  public ReportTransformationReconciliationId getId() {
    return id;
  }
}
