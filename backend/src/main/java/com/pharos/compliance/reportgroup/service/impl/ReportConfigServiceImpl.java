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
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository.ReportConfigDetailsProjection;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository.ReportConfigListProjection;
import com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository.ReportConfigSummaryProjection;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import com.pharos.compliance.reportgroup.service.ReportConfigService;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Service
public class ReportConfigServiceImpl implements ReportConfigService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReportConfigServiceImpl.class);

  private final ReportGroupConfigRepository reportGroupConfigRepository;
  private final CountryCatalog countryCatalog;
  private final Scheduler jdbcScheduler;

  public ReportConfigServiceImpl(
      ReportGroupConfigRepository reportGroupConfigRepository,
      CountryCatalog countryCatalog,
      @Qualifier("jdbcScheduler") Scheduler jdbcScheduler) {
    this.reportGroupConfigRepository = reportGroupConfigRepository;
    this.countryCatalog = countryCatalog;
    this.jdbcScheduler = jdbcScheduler;
  }

  @Override
  public Mono<ReportConfigFilterOptionsResponse> getFilterOptions() {
    return onJdbcScheduler(
        () -> {
          CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
          List<ReportConfigCountryOptionResponse> countries =
              catalog.countries().stream()
                  .map(
                      country ->
                          new ReportConfigCountryOptionResponse(country.code(), country.name()))
                  .toList();
          List<String> reportTypes =
              reportGroupConfigRepository.findActiveReportTypes().stream()
                  .map(type -> type.getReportType())
                  .toList();
          return new ReportConfigFilterOptionsResponse(countries, reportTypes);
        });
  }

  @Override
  public Mono<ReportConfigExplorerResponse> getReportConfigs(
      String country, ReportConfigStatus status, String reportType, Integer reportGroupId) {
    String normalizedCountry = normalizeCountry(country);
    String normalizedReportType = normalizeFilter(reportType);

    return onJdbcScheduler(
            () -> {
              CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
              validateCountry(catalog, normalizedCountry);
              ReportConfigSummaryProjection summary =
                  reportGroupConfigRepository.getSummary(
                      normalizedCountry, status.name(), normalizedReportType, reportGroupId);
              List<ReportConfigListItemResponse> configurations =
                  reportGroupConfigRepository
                      .findReportConfigs(
                          normalizedCountry, status.name(), normalizedReportType, reportGroupId)
                      .stream()
                      .map(this::toListItem)
                      .toList();
              return new ReportConfigExplorerResponse(
                  toSummary(summary),
                  configurations,
                  normalizedCountry,
                  status,
                  normalizedReportType,
                  reportGroupId);
            })
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Report configuration query started country={} status={} reportType={} reportGroupId={}",
                    normalizedCountry,
                    status,
                    normalizedReportType,
                    reportGroupId))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "Report configuration query completed matchingConfigurations={}",
                    response.configurations().size()));
  }

  @Override
  public Mono<ReportConfigDetailsResponse> getReportConfigDetails(
      int reportGroupId, int reportSelectionVersionId, String transformerVersionId) {
    return onJdbcScheduler(
            () ->
                reportGroupConfigRepository
                    .findReportConfigDetails(
                        reportGroupId, reportSelectionVersionId, transformerVersionId)
                    .orElseThrow(
                        () ->
                            new ResourceNotFoundException(
                                "Report-group configuration was not found")))
        .map(this::toDetails)
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Report configuration details query started reportGroupId={} selectionVersion={} transformerVersion={}",
                    reportGroupId,
                    reportSelectionVersionId,
                    transformerVersionId));
  }

  private ReportConfigSummaryResponse toSummary(ReportConfigSummaryProjection summary) {
    return new ReportConfigSummaryResponse(
        summary.getTotalConfigurations(),
        summary.getActiveConfigurations(),
        summary.getRepresentedCountries(),
        summary.getObjectiveConfigurations());
  }

  private ReportConfigListItemResponse toListItem(ReportConfigListProjection config) {
    return new ReportConfigListItemResponse(
        config.getReportGroupId(),
        config.getReportGroupName(),
        config.getReportSelectionVersionId(),
        config.getTransformerVersionId(),
        config.getCountryCode(),
        config.getCountryName(),
        config.getRegionName(),
        config.getReportType(),
        config.getActive(),
        config.getPartialReport(),
        config.getDatabaseLookupEnabled(),
        config.getMappingServiceName(),
        config.getModifiedAt());
  }

  private ReportConfigDetailsResponse toDetails(ReportConfigDetailsProjection config) {
    return new ReportConfigDetailsResponse(
        new ReportConfigDetailsResponse.Identity(
            config.getReportGroupId(),
            config.getReportGroupName(),
            config.getBusinessGroupName(),
            config.getCountryCode(),
            config.getCountryName(),
            config.getThreeLetterCountryCode(),
            config.getRegionCode(),
            config.getRegionName(),
            config.getReportCurrency(),
            config.getReportType(),
            config.getActive()),
        new ReportConfigDetailsResponse.Versioning(
            config.getReportSelectionVersionId(),
            config.getTransformerVersionId(),
            config.getCreatedAt(),
            config.getModifiedAt()),
        new ReportConfigDetailsResponse.ProcessingBehavior(
            config.getDatabaseLookupEnabled(),
            config.getBlankReport(),
            config.getNonTransactionalReport(),
            config.getPartialReport(),
            config.getReportPeriod(),
            config.getAdditionalData()),
        new ReportConfigDetailsResponse.Mapping(
            config.getMappingProjectKey(),
            config.getMappingServiceName(),
            config.getAcknowledgementDocumentSubtype(),
            config.getOutputFileDocumentSubtype(),
            config.getSubmissionDocumentSubtype(),
            config.getTransformerConfig()),
        new ReportConfigDetailsResponse.Rules(
            config.getInboundRuleId(),
            config.getOutboundRuleId(),
            config.getReportSelection(),
            config.getReportableActivityColumns(),
            config.getRuleHitColumns()),
        new ReportConfigDetailsResponse.Strategies(
            config.getExclusionStrategy(),
            config.getExclusionReason(),
            config.getColumnToCompare(),
            config.getManipulationStrategyMetadata(),
            config.getReconciliationStrategyMetadata()));
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

  private <T> Mono<T> onJdbcScheduler(Callable<T> databaseCall) {
    return Mono.fromCallable(databaseCall).subscribeOn(jdbcScheduler);
  }
}
