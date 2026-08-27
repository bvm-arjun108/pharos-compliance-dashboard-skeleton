package com.pharos.compliance.reportgroup.service.impl;

import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository.CountryMappingProjection;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCountryCatalog implements CountryCatalog {

  private final ReportGroupConfigRepository reportGroupConfigRepository;

  public DatabaseCountryCatalog(ReportGroupConfigRepository reportGroupConfigRepository) {
    this.reportGroupConfigRepository = reportGroupConfigRepository;
  }

  @Override
  public CountryCatalogSnapshot getSnapshot() {
    Map<String, CountryAccumulator> countriesByCode = new LinkedHashMap<>();
    for (CountryMappingProjection mapping :
        reportGroupConfigRepository.findCountryMappings()) {
      countriesByCode
          .computeIfAbsent(
              mapping.getCountryCode(),
              ignored -> new CountryAccumulator(mapping.getCountryCode(), mapping.getCountryName()))
          .reportGroupIds()
          .add(mapping.getReportGroupId());
    }

    List<CountryDefinition> countries = new ArrayList<>(countriesByCode.size());
    countriesByCode
        .values()
        .forEach(
            country ->
                countries.add(
                    new CountryDefinition(
                        country.code(), country.name(), country.reportGroupIds())));
    return new CountryCatalogSnapshot(countries);
  }

  private record CountryAccumulator(String code, String name, TreeSet<Integer> reportGroupIds) {

    private CountryAccumulator(String code, String name) {
      this(code, name, new TreeSet<>());
    }
  }
}
