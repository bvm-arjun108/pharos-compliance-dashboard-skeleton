package com.pharos.compliance.reportgroup.service.impl;

import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.reportgroup.dto.ReportConfigCountryOptionResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigListItemResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigSummaryResponse;
import com.pharos.compliance.reportgroup.dto.ReportGroupOptionResponse;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigDetailsProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigListProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigSummaryProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportGroupOptionProjection;
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
    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    List<ReportConfigCountryOptionResponse> countryOptions = countryCatalogSnapshot
      .countries()
      .stream()
      .map(country -> new ReportConfigCountryOptionResponse(country.code(), country.name()))
      .toList();
    List<String> reportTypes = reportGroupConfigRepository
      .findReportTypes()
      .stream()
      .map(type -> type.reportType())
      .toList();
    return new ReportConfigFilterOptionsResponse(countryOptions, reportTypes);
  }

  @Override
  public List<ReportGroupOptionResponse> getReportGroupOptions() {
    return reportGroupConfigRepository.findReportGroupOptions().stream().map(this::toReportGroupOption).toList();
  }

  @Override
  public ReportConfigExplorerResponse getReportConfigs(String country, ReportConfigStatus status, String reportType, Integer reportGroupId) {
    String normalizedCountry = normalizeCountry(country);
    String normalizedReportType = normalizeFilter(reportType);
    long operationStartedAtNanos = System.nanoTime();

    LOGGER.debug("Report configuration scope resolved | country={} | status={} | reportType={} | reportGroupId={}", normalizedCountry,
        status, normalizedReportType, reportGroupId == null ? "ALL" : reportGroupId);

    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    validateCountry(countryCatalogSnapshot, normalizedCountry);
    ReportConfigSummaryProjection configurationSummary =
        reportGroupConfigRepository.getSummary(normalizedCountry, status.name(), normalizedReportType, reportGroupId);
    List<ReportConfigListItemResponse> matchingConfigurations = reportGroupConfigRepository
      .findReportConfigs(normalizedCountry, status.name(), normalizedReportType, reportGroupId)
      .stream()
      .map(this::toListItem)
      .toList();
    ReportConfigExplorerResponse reportConfigExplorerResponse = new ReportConfigExplorerResponse(toSummary(configurationSummary),
        matchingConfigurations, normalizedCountry, status, normalizedReportType, reportGroupId);

    LOGGER.info("Report configuration catalog ready | country={} | status={} | reportType={} | reportGroupId={} | matched={} | total={}"
        + " | active={} | representedCountries={} | duration={}ms", normalizedCountry, status, normalizedReportType,
        reportGroupId == null ? "ALL" : reportGroupId, reportConfigExplorerResponse.configurations().size(),
        reportConfigExplorerResponse.summary().totalConfigurations(), reportConfigExplorerResponse.summary().activeConfigurations(),
        reportConfigExplorerResponse.summary().representedCountries(), (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    return reportConfigExplorerResponse;
  }

  @Override
  public ReportConfigDetailsResponse getReportConfigDetails(int reportGroupId, int reportSelectionVersionId, String transformerVersionId) {
    long operationStartedAtNanos = System.nanoTime();
    LOGGER.debug("Report configuration details requested | reportGroupId={} | selectionVersion={} | transformerVersion={}", reportGroupId,
        reportSelectionVersionId, transformerVersionId);

    ReportConfigDetailsProjection reportConfigDetails = reportGroupConfigRepository
      .findReportConfigDetails(reportGroupId, reportSelectionVersionId, transformerVersionId)
      .orElseThrow(() -> new ResourceNotFoundException("Report-group configuration was not found"));
    ReportConfigDetailsResponse reportConfigDetailsResponse = toDetails(reportConfigDetails);
    LOGGER.info("Report configuration details ready | reportGroupId={} | reportGroupName={} | country={} | reportType={} | active={}"
        + " | selectionVersion={} | transformerVersion={} | duration={}ms", reportGroupId, reportConfigDetails.reportGroupName(),
        reportConfigDetails.countryCode(), reportConfigDetails.reportType(), reportConfigDetails.active(), reportSelectionVersionId,
        transformerVersionId, (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    return reportConfigDetailsResponse;
  }

  private ReportGroupOptionResponse toReportGroupOption(ReportGroupOptionProjection reportGroupOption) {
    return new ReportGroupOptionResponse(reportGroupOption.reportGroupId(), reportGroupOption.reportGroupName(),
        reportGroupOption.countryCode());
  }

  private ReportConfigSummaryResponse toSummary(ReportConfigSummaryProjection configurationSummary) {
    return new ReportConfigSummaryResponse(configurationSummary.totalConfigurations(), configurationSummary.activeConfigurations(),
        configurationSummary.representedCountries(), configurationSummary.objectiveConfigurations(),
        configurationSummary.subjectiveConfigurations());
  }

  private ReportConfigListItemResponse toListItem(ReportConfigListProjection reportConfig) {
    return new ReportConfigListItemResponse(reportConfig.reportGroupId(), reportConfig.reportGroupName(),
        reportConfig.reportSelectionVersionId(), reportConfig.transformerVersionId(), reportConfig.countryCode(), reportConfig.countryName(),
        reportConfig.regionName(), reportConfig.reportType(), reportConfig.active(), reportConfig.partialReport(),
        reportConfig.databaseLookupEnabled(), reportConfig.mappingServiceName(), reportConfig.modifiedAt());
  }

  private ReportConfigDetailsResponse toDetails(ReportConfigDetailsProjection reportConfig) {
    return new ReportConfigDetailsResponse(new ReportConfigDetailsResponse.Identity(reportConfig.reportGroupId(),
            reportConfig.reportGroupName(), reportConfig.businessGroupName(), reportConfig.countryCode(), reportConfig.countryName(),
            reportConfig.threeLetterCountryCode(), reportConfig.regionCode(), reportConfig.regionName(), reportConfig.reportCurrency(),
            reportConfig.reportType(), reportConfig.active()),
        new ReportConfigDetailsResponse.Versioning(reportConfig.reportSelectionVersionId(), reportConfig.transformerVersionId(),
            reportConfig.createdAt(), reportConfig.modifiedAt()),
        new ReportConfigDetailsResponse.ProcessingBehavior(reportConfig.databaseLookupEnabled(), reportConfig.blankReport(),
            reportConfig.nonTransactionalReport(), reportConfig.partialReport(), reportConfig.reportPeriod(), reportConfig.additionalData()),
        new ReportConfigDetailsResponse.Mapping(reportConfig.mappingProjectKey(), reportConfig.mappingServiceName(),
            reportConfig.acknowledgementDocumentSubtype(), reportConfig.outputFileDocumentSubtype(),
            reportConfig.submissionDocumentSubtype(), reportConfig.transformerConfig()),
        new ReportConfigDetailsResponse.Rules(reportConfig.inboundRuleId(), reportConfig.outboundRuleId(), reportConfig.reportSelection(),
            reportConfig.reportableActivityColumns(), reportConfig.ruleHitColumns()),
        new ReportConfigDetailsResponse.Strategies(reportConfig.exclusionStrategy(), reportConfig.exclusionReason(),
            reportConfig.columnToCompare(), reportConfig.manipulationStrategyMetadata(), reportConfig.reconciliationStrategyMetadata()));
  }

  private void validateCountry(CountryCatalogSnapshot countryCatalogSnapshot, String country) {
    if (!"ALL".equals(country) && countryCatalogSnapshot.findByCode(country).isEmpty()) {
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
