package com.wu.compliance.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import com.wu.compliance.dashboard.testsupport.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the real application (controllers, services, caches, filters, error handling, JDBC wiring)
 * against the schema-only Testcontainers database and calls every endpoint over HTTP. The database
 * is empty, so these assert routing, binding, validation and error mapping rather than data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiEndpointsIntegrationTest extends PostgresIntegrationTest {
  private static final String PERIOD = "fromDate=2026-09-01&toDate=2026-09-30";
  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("common.postgres.url", POSTGRES::getJdbcUrl);
    registry.add("common.postgres.username", POSTGRES::getUsername);
    registry.add("common.postgres.password", POSTGRES::getPassword);
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient
      .newHttpClient()
      .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
  }

  private void assertStatus(String pathAndQuery, int expected) throws Exception {
    HttpResponse<String> response = get(pathAndQuery);
    assertThat(response.statusCode()).as(pathAndQuery + " -> " + response.body()).isEqualTo(expected);
  }

  @Test
  void healthReportsTheDatabaseAndEchoesTraceHeaders() throws Exception {
    HttpResponse<String> response = get("/api/v1/health");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
    assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
  }

  @Test
  void dashboardEndpoints() throws Exception {
    assertStatus("/dashboardDetails/batch-view?" + PERIOD, 200);
    assertStatus("/dashboardDetails/batch-view?" + PERIOD + "&batchId=B1&country=ALL&reportGroupId=1", 200);
    assertStatus("/dashboardDetails/transaction-view?" + PERIOD, 200);
    assertStatus("/dashboardDetails/transaction-view?" + PERIOD + "&reportGroupId=1", 200);
  }

  @Test
  void batchEndpoints() throws Exception {
    assertStatus("/api/v1/batches/filter-options", 200);
    assertStatus("/api/v1/batches?" + PERIOD, 200);
    assertStatus("/api/v1/batches?" + PERIOD + "&status=ATTENTION&issueType=EXCLUSION&metricFocus=EXCLUDED&batchId=B", 200);
    assertStatus("/api/v1/batches/1/B1/1", 404);
  }

  @Test
  void reportConfigEndpoints() throws Exception {
    assertStatus("/api/v1/report-configs/filter-options", 200);
    assertStatus("/api/v1/report-configs/report-groups", 200);
    assertStatus("/api/v1/report-configs", 200);
    assertStatus("/api/v1/report-configs?status=ACTIVE&reportType=CTR&reportGroupId=1", 200);
    assertStatus("/api/v1/report-configs/1/1/tv", 404);
  }

  @Test
  void transactionEndpoints() throws Exception {
    assertStatus("/api/v1/transactions/report?reportGroupId=1&batchId=B1&sequenceNumber=1", 404);
    assertStatus("/api/v1/transactions/period-report?" + PERIOD, 200);
    assertStatus("/api/v1/transactions/period-report?" + PERIOD + "&status=EXCLUDED&batchScopedExcluded=true&reason=x", 200);
    assertStatus("/api/v1/transactions/period-report/batches?" + PERIOD, 200);
    assertStatus("/api/v1/transactions/search?field=MTCN&query=123", 200);
    assertStatus("/api/v1/transactions/search?field=EXTERNAL_TXN_ID&query=123_P&" + PERIOD + "&country=ALL", 200);
    assertStatus("/api/v1/transactions/detail?scope=BATCH&reportGroupId=1&batchId=B1&identifier=1&recordKey=k", 404);
    assertStatus("/api/v1/transactions/detail?scope=PERIOD&batchId=B1&identifier=1&recordKey=k&" + PERIOD, 404);
  }

  @Test
  void invalidRequestsAreMappedToTheStandardErrorBody() throws Exception {
    HttpResponse<String> badRange = get("/api/v1/batches?fromDate=2026-09-30&toDate=2026-09-01");
    assertThat(badRange.statusCode()).isEqualTo(400);
    assertThat(badRange.body()).contains("INVALID_DATE_RANGE");
    assertStatus("/api/v1/batches?fromDate=nope&toDate=2026-09-01", 400);
    assertStatus("/api/v1/batches", 400);
    assertStatus("/api/v1/batches?" + PERIOD + "&country=ZZ", 400);
    assertStatus("/api/v1/transactions/search?field=MTCN&query=%20", 400);
    assertStatus("/api/v1/transactions/search?field=MTCN&query=1&fromDate=2026-09-01", 400);
    assertStatus("/api/v1/transactions/report?reportGroupId=1&batchId=B1&sequenceNumber=1&cursor=***", 400);
    assertStatus("/api/v1/batches/0/B1/1", 400);
    HttpResponse<String> unknown = get("/api/v1/does-not-exist");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.body()).contains("RESOURCE_NOT_FOUND");
  }

  @Test
  void apiDocsAreServed() throws Exception {
    assertStatus("/v3/api-docs", 200);
  }
}
