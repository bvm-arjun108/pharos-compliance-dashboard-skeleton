package com.pharos.compliance.reportgroup.model;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CountryCatalogSnapshot {
  private static final CountryDefinition UNCONFIGURED_COUNTRY = new CountryDefinition("UNCONFIGURED", "Not configured", Set.of());
  private final List<CountryDefinition> countries;
  private final Map<String, CountryDefinition> countriesByCode;
  private final Map<Integer, CountryDefinition> countriesByReportGroup;

  public CountryCatalogSnapshot(List<CountryDefinition> countries) {
    this.countries = List.copyOf(countries);
    this.countriesByCode = indexByCode(this.countries);
    this.countriesByReportGroup = indexByReportGroup(this.countries);
  }

  public List<CountryDefinition> countries() {
    return countries;
  }

  public Optional<CountryDefinition> findByCode(String countryCode) {
    return Optional.ofNullable(countriesByCode.get(countryCode.toUpperCase(Locale.ROOT)));
  }

  public CountryDefinition getForReportGroup(int reportGroupId) {
    return countriesByReportGroup.getOrDefault(reportGroupId, UNCONFIGURED_COUNTRY);
  }

  private Map<String, CountryDefinition> indexByCode(List<CountryDefinition> definitions) {
    Map<String, CountryDefinition> result = new HashMap<>();
    definitions.forEach(country -> result.put(country.code(), country));
    return Map.copyOf(result);
  }

  private Map<Integer, CountryDefinition> indexByReportGroup(List<CountryDefinition> definitions) {
    Map<Integer, CountryDefinition> result = new HashMap<>();
    definitions.forEach(country -> country
      .reportGroupIds()
      .forEach(id -> result.put(id, country)));
    return Map.copyOf(result);
  }
}
