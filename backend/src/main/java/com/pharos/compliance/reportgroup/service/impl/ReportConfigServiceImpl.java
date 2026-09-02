package com.pharos.compliance.reportgroup.service.impl;

import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.reportgroup.dto.ReportConfigCountryOptionResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigListItemResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigSummaryResponse;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigDetailsProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigListProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigSummaryProjection;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import com.pharos.compliance.reportgroup.service.ReportConfigService;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReportConfigServiceImpl implements ReportConfigService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportConfigServiceImpl.class);
  private final ReportGroupConfigRepository reportGroupConfigRepository;
  private final CountryCatalog countryCatalog;

  public ReportConfigServiceImpl(ReportGroupConfigRepository reportGroupConfigRepository, CountryCatalog countryCatalog) {
    this.reportGroupConfigRepository = reportGroupConfigRepository;
    this.countryCatalog = countryCatalog;
  }

  @Override
  public ReportConfigFilterOptionsResponse getFilterOptions() {
    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    List<ReportConfigCountryOptionResponse> countries =
        catalog
      .countries()
      .stream()
      .map(country -> new ReportConfigCountryOptionResponse(country.code(), country.name()))
      .toList();
    List<String> reportTypes = reportGroupConfigRepository
      .findReportTypes()
      .stream()
      .map(type -> type.reportType())
      .toList();
    return new ReportConfigFilterOptionsResponse(countries, reportTypes);
  }

  @Override
  public ReportConfigExplorerResponse getReportConfigs(String country, ReportConfigStatus status, String reportType, Integer reportGroupId) {
    String normalizedCountry = normalizeCountry(country);
    String normalizedReportType = normalizeFilter(reportType);
    long startedAt = System.nanoTime();

    LOGGER.debug("Report configuration scope resolved | country={} | status={} | reportType={} | reportGroupId={}", normalizedCountry,
        status, normalizedReportType, reportGroupId == null ? "ALL" : reportGroupId);

    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    validateCountry(catalog, normalizedCountry);
    ReportConfigSummaryProjection summary =
        reportGroupConfigRepository.getSummary(normalizedCountry, status.name(), normalizedReportType, reportGroupId);
    List<ReportConfigListItemResponse> configurations = reportGroupConfigRepository
      .findReportConfigs(normalizedCountry, status.name(), normalizedReportType, reportGroupId)
      .stream()
      .map(this::toListItem)
      .toList();
    ReportConfigExplorerResponse response =
        new ReportConfigExplorerResponse(toSummary(summary), configurations, normalizedCountry, status, normalizedReportType, reportGroupId);

    LOGGER.info("Report configuration catalog ready | country={} | status={} | reportType={} | reportGroupId={} | matched={} | total={}"
        + " | active={} | representedCountries={} | duration={}ms", normalizedCountry, status, normalizedReportType,
        reportGroupId == null ? "ALL" : reportGroupId, response.configurations().size(), response.summary().totalConfigurations(),
        response.summary().activeConfigurations(), response.summary().representedCountries(), (System.nanoTime() - startedAt) / 1_000_000);
    return response;
  }

  @Override
  public ReportConfigDetailsResponse getReportConfigDetails(int reportGroupId, int reportSelectionVersionId, String transformerVersionId) {
    long startedAt = System.nanoTime();
    LOGGER.debug("Report configuration details requested | reportGroupId={} | selectionVersion={} | transformerVersion={}", reportGroupId,
        reportSelectionVersionId, transformerVersionId);

    ReportConfigDetailsProjection config = reportGroupConfigRepository
      .findReportConfigDetails(reportGroupId, reportSelectionVersionId, transformerVersionId)
      .orElseThrow(() -> new ResourceNotFoundException("Report-group configuration was not found"));
    ReportConfigDetailsResponse response = toDetails(config);
    LOGGER.info("Report configuration details ready | reportGroupId={} | reportGroupName={} | country={} | reportType={} | active={}"
        + " | selectionVersion={} | transformerVersion={} | duration={}ms", reportGroupId, config.reportGroupName(), config.countryCode(),
        config.reportType(), config.active(), reportSelectionVersionId, transformerVersionId, (System.nanoTime() - startedAt) / 1_000_000);
    return response;
  }

  private ReportConfigSummaryResponse toSummary(ReportConfigSummaryProjection summary) {
    return new ReportConfigSummaryResponse(summary.totalConfigurations(), summary.activeConfigurations(), summary.representedCountries(),
        summary.objectiveConfigurations());
  }

  private ReportConfigListItemResponse toListItem(ReportConfigListProjection config) {
    return new ReportConfigListItemResponse(config.reportGroupId(), config.reportGroupName(), config.reportSelectionVersionId(),
        config.transformerVersionId(), config.countryCode(), config.countryName(), config.regionName(), config.reportType(), config.active(),
        config.partialReport(), config.databaseLookupEnabled(), config.mappingServiceName(), config.modifiedAt());
  }

  private ReportConfigDetailsResponse toDetails(ReportConfigDetailsProjection config) {
    return new ReportConfigDetailsResponse(new ReportConfigDetailsResponse.Identity(config.reportGroupId(), config.reportGroupName(),
            config.businessGroupName(), config.countryCode(), config.countryName(), config.threeLetterCountryCode(), config.regionCode(),
            config.regionName(), config.reportCurrency(), config.reportType(), config.active()),
        new ReportConfigDetailsResponse.Versioning(config.reportSelectionVersionId(), config.transformerVersionId(), config.createdAt(),
            config.modifiedAt()),
        new ReportConfigDetailsResponse.ProcessingBehavior(config.databaseLookupEnabled(), config.blankReport(),
            config.nonTransactionalReport(), config.partialReport(), config.reportPeriod(), config.additionalData()),
        new ReportConfigDetailsResponse.Mapping(config.mappingProjectKey(), config.mappingServiceName(),
            config.acknowledgementDocumentSubtype(), config.outputFileDocumentSubtype(), config.submissionDocumentSubtype(),
            config.transformerConfig()),
        new ReportConfigDetailsResponse.Rules(config.inboundRuleId(), config.outboundRuleId(), config.reportSelection(),
            config.reportableActivityColumns(), config.ruleHitColumns()),
        new ReportConfigDetailsResponse.Strategies(config.exclusionStrategy(), config.exclusionReason(), config.columnToCompare(),
            config.manipulationStrategyMetadata(), config.reconciliationStrategyMetadata()));
  }

  private void validateCountry(CountryCatalogSnapshot catalog, String country) {
    if (!"ALL".equals(country) && catalog.findByCode(country).isEmpty()) {
      throw new InvalidRequestException("Unsupported country filter: " + country);
    }
  }

  private String normalizeCountry(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeFilter(String value) {
    return value == null || value.isBlank() ? "ALL" : value.trim();
  }
}
