package com.pharos.compliance.reportgroup.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "report_group_config", schema = "pharos")
public class ReportGroupConfigEntity {

  @EmbeddedId private ReportGroupConfigId id;

  protected ReportGroupConfigEntity() {}

  public ReportGroupConfigId getId() {
    return id;
  }
}
