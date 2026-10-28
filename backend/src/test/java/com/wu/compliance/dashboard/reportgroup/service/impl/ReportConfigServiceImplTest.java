package com.wu.compliance.dashboard.reportgroup.service.impl;

import com.wu.compliance.dashboard.testsupport.DebugLogging;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.common.exception.ResourceNotFoundException;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigDetailsResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigExplorerResponse;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import com.wu.compliance.dashboard.reportgroup.model.ReportConfigStatus;
import com.wu.compliance.dashboard.reportgroup.repository.ReportGroupConfigRepository;
import com.wu.compliance.dashboard.reportgroup.repository.projection.CountryMappingProjection;
import com.wu.compliance.dashboard.reportgroup.repository.projection.ReportConfigDetailsProjection;
import com.wu.compliance.dashboard.reportgroup.repository.projection.ReportConfigListProjection;
import com.wu.compliance.dashboard.reportgroup.repository.projection.ReportConfigSummaryProjection;
import com.wu.compliance.dashboard.reportgroup.repository.projection.ReportGroupOptionProjection;
import com.wu.compliance.dashboard.reportgroup.repository.projection.ReportTypeProjection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportConfigServiceImplTest {
  private ReportGroupConfigRepository repository;
  private ReportConfigServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(ReportGroupConfigRepository.class);
    CountryCatalogSnapshot snapshot = new CountryCatalogSnapshot(List.of(new CountryDefinition("US", "United States", Set.of(1))));
    service = new ReportConfigServiceImpl(repository, () -> snapshot);
  }

  @Test
  void filterOptionsCombineCountriesAndReportTypes() {
    when(repository.findReportTypes()).thenReturn(List.of(new ReportTypeProjection("CTR"), new ReportTypeProjection("SAR")));
    var options = service.getFilterOptions();
    assertThat(options.countries()).extracting("code").containsExactly("US");
    assertThat(options.reportTypes()).containsExactly("CTR", "SAR");
  }

  @Test
  void reportGroupOptionsAreMapped() {
    when(repository.findReportGroupOptions()).thenReturn(List.of(new ReportGroupOptionProjection(1, "RG1", "US")));
    assertThat(service.getReportGroupOptions())
      .singleElement()
      .satisfies(option -> {
        assertThat(option.reportGroupId()).isEqualTo(1);
        assertThat(option.countryCode()).isEqualTo("US");
      });
  }

  @Test
  void reportConfigsNormalizeBlankFilters() {
    when(repository.getSummary("ALL", "ALL", "ALL", null)).thenReturn(new ReportConfigSummaryProjection(2, 1, 1, 1, 1));
    when(repository.findReportConfigs("ALL", "ALL", "ALL", null))
      .thenReturn(List.of(
          new ReportConfigListProjection(1, "RG1", 2, "tv", "US", "United States", "NA", "CTR", true, false, true, "svc", Instant.EPOCH)));
    ReportConfigExplorerResponse response = service.getReportConfigs(null, ReportConfigStatus.ALL, "  ", null);
    assertThat(response.country()).isEqualTo("ALL");
    assertThat(response.reportType()).isEqualTo("ALL");
    assertThat(response.summary().totalConfigurations()).isEqualTo(2);
    assertThat(response.configurations()).hasSize(1);
    assertThat(response.configurations().get(0).mappingServiceName()).isEqualTo("svc");
  }

  @Test
  void reportConfigsForAKnownCountry() {
    when(repository.getSummary("US", "ACTIVE", "CTR", 1)).thenReturn(new ReportConfigSummaryProjection(1, 1, 1, 1, 0));
    when(repository.findReportConfigs("US", "ACTIVE", "CTR", 1)).thenReturn(List.of());
    ReportConfigExplorerResponse response = service.getReportConfigs(" us ", ReportConfigStatus.ACTIVE, " CTR ", 1);
    assertThat(response.country()).isEqualTo("US");
    verify(repository).findReportConfigs("US", "ACTIVE", "CTR", 1);
  }

  @Test
  void reportConfigsRejectAnUnknownCountry() {
    assertThatThrownBy(() -> service.getReportConfigs("ZZ", ReportConfigStatus.ALL, null, null)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void detailsAreMappedIntoSections() {
    Instant now = Instant.parse("2026-09-01T00:00:00Z");
    when(repository.findReportConfigDetails(1, 2, "tv"))
      .thenReturn(Optional.of(
          new ReportConfigDetailsProjection(1, "RG1", "BG", "US", "United States", "USA", "NA", "North America", "USD", "CTR", true, 2, "tv",
              now, now, true, false, false, true, 30, "extra", "pk", "svc", "ack", "out", "sub", "{}", "in", "outb", "sel", "cols", "hit",
              "strat", "reason", "cmp", "man", "rec")));
    ReportConfigDetailsResponse response = service.getReportConfigDetails(1, 2, "tv");
    assertThat(response.identity().reportGroupName()).isEqualTo("RG1");
    assertThat(response.versioning().transformerVersionId()).isEqualTo("tv");
    assertThat(response.processingBehavior().reportPeriod()).isEqualTo(30);
    assertThat(response.mapping().projectKey()).isEqualTo("pk");
    assertThat(response.rules().ruleHitColumns()).isEqualTo("hit");
    assertThat(response.strategies().columnToCompare()).isEqualTo("cmp");
  }

  @Test
  void unknownDetailsAreNotFound() {
    when(repository.findReportConfigDetails(1, 2, null)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getReportConfigDetails(1, 2, null)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void countryCatalogGroupsMappingsByCountry() {
    when(repository.findCountryMappings())
      .thenReturn(List.of(new CountryMappingProjection("US", "United States", 2), new CountryMappingProjection("CA", "Canada", 3),
          new CountryMappingProjection("US", "United States", 1)));
    CountryCatalogSnapshot snapshot = new DatabaseCountryCatalog(repository).getSnapshot();
    assertThat(snapshot.countries()).extracting(CountryDefinition::code).containsExactly("US", "CA");
    assertThat(snapshot.findByCode("us")).get().extracting(CountryDefinition::reportGroupIds).isEqualTo(Set.of(1, 2));
    assertThat(snapshot.getForReportGroup(3).code()).isEqualTo("CA");
    assertThat(snapshot.getForReportGroup(99).code()).isEqualTo("UNCONFIGURED");
  }

  @Test
  void debugLoggingDoesNotChangeResults() {
    try (DebugLogging ignored = DebugLogging.enable()) {
      when(repository.getSummary("ALL", "ALL", "ALL", null)).thenReturn(new ReportConfigSummaryProjection(0, 0, 0, 0, 0));
      when(repository.findReportConfigs("ALL", "ALL", "ALL", null)).thenReturn(List.of());
      assertThat(service.getReportConfigs(null, ReportConfigStatus.ALL, null, null).configurations()).isEmpty();
    }
  }
}
