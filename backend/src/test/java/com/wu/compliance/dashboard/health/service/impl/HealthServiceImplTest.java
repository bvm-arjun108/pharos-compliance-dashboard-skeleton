package com.wu.compliance.dashboard.health.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.common.exception.DatabaseUnavailableException;
import com.wu.compliance.dashboard.health.dto.HealthResponse;
import com.wu.compliance.dashboard.health.repository.DatabaseHealthRepository;
import com.wu.compliance.dashboard.health.repository.projection.DatabaseMetadata;
import org.junit.jupiter.api.Test;

class HealthServiceImplTest {
  private final DatabaseHealthRepository repository = mock(DatabaseHealthRepository.class);
  private final HealthServiceImpl service = new HealthServiceImpl(repository);

  @Test
  void reportsUpWithDatabaseMetadata() {
    when(repository.getDatabaseMetadata()).thenReturn(new DatabaseMetadata("db", "pharos"));
    HealthResponse response = service.getHealth();
    assertThat(response.status()).isEqualTo("UP");
    assertThat(response.database()).isEqualTo("db");
    assertThat(response.schema()).isEqualTo("pharos");
    assertThat(response.timestamp()).isNotNull();
  }

  @Test
  void passesThroughDatabaseUnavailable() {
    DatabaseUnavailableException failure = new DatabaseUnavailableException("down", new RuntimeException());
    when(repository.getDatabaseMetadata()).thenThrow(failure);
    assertThatThrownBy(service::getHealth).isSameAs(failure);
  }

  @Test
  void wrapsAnyOtherFailure() {
    when(repository.getDatabaseMetadata()).thenThrow(new IllegalStateException("boom"));
    assertThatThrownBy(service::getHealth).isInstanceOf(DatabaseUnavailableException.class).hasCauseInstanceOf(IllegalStateException.class);
  }
}
