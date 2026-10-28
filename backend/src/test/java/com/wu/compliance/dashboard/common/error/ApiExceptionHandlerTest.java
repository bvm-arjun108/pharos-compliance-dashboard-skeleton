package com.wu.compliance.dashboard.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.common.exception.DatabaseUnavailableException;
import com.wu.compliance.dashboard.common.exception.InvalidDateRangeException;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.common.exception.ResourceNotFoundException;
import com.wu.compliance.dashboard.common.tracing.TraceContextAccessor;
import com.wu.compliance.dashboard.common.tracing.TraceContextAccessor.TraceIdentifiers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class ApiExceptionHandlerTest {
  private final TraceContextAccessor traces = mock(TraceContextAccessor.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final ApiExceptionHandler handler = new ApiExceptionHandler(traces);

  ApiExceptionHandlerTest() {
    when(traces.current()).thenReturn(new TraceIdentifiers("trace", "span"));
    when(request.getRequestURI()).thenReturn("/api/x");
  }

  private static void assertBody(ResponseEntity<ApiErrorResponse> response, int status, String code) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    ApiErrorResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.code()).isEqualTo(code);
    assertThat(body.status()).isEqualTo(status);
    assertThat(body.path()).isEqualTo("/api/x");
    assertThat(body.traceId()).isEqualTo("trace");
    assertThat(body.spanId()).isEqualTo("span");
    assertThat(body.timestamp()).isNotNull();
    assertThat(body.error()).isNotBlank();
  }

  @Test
  void clientErrors() {
    var dateRange = handler.handleInvalidDateRange(new InvalidDateRangeException("bad range"), request);
    assertBody(dateRange, 400, "INVALID_DATE_RANGE");
    assertThat(dateRange.getBody().message()).isEqualTo("bad range");
    assertBody(handler.handleInvalidRequest(new InvalidRequestException("nope"), request), 400, "INVALID_REQUEST");
    assertBody(handler.handleResourceNotFound(new ResourceNotFoundException("gone"), request), 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void unknownPathsAre404NotServerErrors() {
    var missing = handler.handleUnknownPath(new org.springframework.web.servlet.resource.NoResourceFoundException(org.springframework.http.HttpMethod.GET,
            "/api/x", "api/x"), request);
    assertBody(missing, 404, "RESOURCE_NOT_FOUND");
    assertThat(missing.getBody().message()).doesNotContain("static resource");
  }

  @Test
  void requestValidationMessages() {
    var mismatch = new MethodArgumentTypeMismatchException("abc", Integer.class, "page", null, null);
    assertThat(handler.handleInvalidRequest((Exception) mismatch, request).getBody().message())
      .isEqualTo("Invalid value 'abc' for parameter 'page'");
    assertThat(handler
      .handleInvalidRequest((Exception) new ConstraintViolationException("size must be >= 1", Set.of()), request)
      .getBody()
      .message())
      .isEqualTo("size must be >= 1");
    assertThat(handler.handleInvalidRequest((Exception) new ConstraintViolationException(null, Set.of()), request).getBody().message())
      .isEqualTo("The request is invalid");
  }

  @Test
  void serverErrorsNeverLeakTheirMessage() {
    var database = handler.handleDatabaseUnavailable(new DatabaseUnavailableException("secret host", new RuntimeException()), request);
    assertBody(database, 503, "DATABASE_UNAVAILABLE");
    assertThat(database.getBody().message()).doesNotContain("secret");
    assertBody(handler.handleDatabaseUnavailable(new DataRetrievalFailureException("x"), request), 503, "DATABASE_UNAVAILABLE");
    var unexpected = handler.handleUnexpectedError(new IllegalStateException("internal detail"), request);
    assertBody(unexpected, 500, "INTERNAL_ERROR");
    assertThat(unexpected.getBody().message()).doesNotContain("internal detail");
  }
}
