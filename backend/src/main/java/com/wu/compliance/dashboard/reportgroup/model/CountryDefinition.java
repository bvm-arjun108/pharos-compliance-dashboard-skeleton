package com.wu.compliance.dashboard.reportgroup.model;

import java.util.Set;

public record CountryDefinition(String code, String name, Set<Integer> reportGroupIds) {
  public CountryDefinition {
    reportGroupIds = Set.copyOf(reportGroupIds);
  }
}
