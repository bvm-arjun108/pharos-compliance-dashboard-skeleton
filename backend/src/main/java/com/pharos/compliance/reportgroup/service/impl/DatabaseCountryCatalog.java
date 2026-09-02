package com.pharos.compliance.reportgroup.service.impl;

import com.pharos.compliance.config.CacheConfiguration;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository;
import com.pharos.compliance.reportgroup.repository.projection.CountryMappingProjection;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Report-group configuration has no write path yet, so the country-to-report-group mapping derived
 * from it cannot change during the application's lifetime today; see the {@link CacheConfiguration}
 * Javadoc for the caching rationale and what to revisit once writes exist. {@code getSnapshot()}
 * takes no arguments, so Spring's default key generator caches it under a single fixed key -- there
 * is no per-argument key space to manage here, unlike the transaction evidence caches.
 *
 * <p>{@code sync = true} matters more than usual for this one method: with the default async
 * behavior, several requests that all miss the cache at the same time (e.g. a page load firing a
 * few API calls in parallel, exactly what happens on every cache-empty/expired moment) would each
 * independently re-run the query before any of them finishes populating the cache. Synchronizing
 * blocks the racing callers on the first miss so the query runs exactly once per expiry, not once
 * per concurrent caller.
 */
@Component
public class DatabaseCountryCatalog implements CountryCatalog {
  private final ReportGroupConfigRepository reportGroupConfigRepository;

  public DatabaseCountryCatalog(ReportGroupConfigRepository reportGroupConfigRepository) {
    this.reportGroupConfigRepository = reportGroupConfigRepository;
  }

  @Override
  @Cacheable(cacheNames = CacheConfiguration.COUNTRY_CATALOG, sync = true)
  public CountryCatalogSnapshot getSnapshot() {
    Map<String, CountryAccumulator> countriesByCode = new LinkedHashMap<>();
    for (CountryMappingProjection mapping : reportGroupConfigRepository.findCountryMappings()) {
      countriesByCode
        .computeIfAbsent(mapping.countryCode(), ignored -> new CountryAccumulator(mapping.countryCode(), mapping.countryName()))
        .reportGroupIds()
        .add(mapping.reportGroupId());
    }

    List<CountryDefinition> countries = new ArrayList<>(countriesByCode.size());
    countriesByCode
      .values()
      .forEach(country -> countries.add(new CountryDefinition(country.code(), country.name(), country.reportGroupIds())));
    return new CountryCatalogSnapshot(countries);
  }

  private record CountryAccumulator(String code, String name, TreeSet<Integer> reportGroupIds) {
    private CountryAccumulator(String code, String name) {
      this(code, name, new TreeSet<>());
    }
  }
}
