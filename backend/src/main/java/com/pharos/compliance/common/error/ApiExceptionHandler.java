package com.pharos.compliance.common.error;

import com.pharos.compliance.common.exception.DatabaseUnavailableException;
import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.common.tracing.TraceContextAccessor;
import com.pharos.compliance.common.tracing.TraceContextAccessor.TraceIdentifiers;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final String GENERIC_ERROR_MESSAGE =
      "An unexpected error occurred while processing the request";

  private final TraceContextAccessor traceContextAccessor;

  public ApiExceptionHandler(TraceContextAccessor traceContextAccessor) {
    this.traceContextAccessor = traceContextAccessor;
  }

  @ExceptionHandler(InvalidDateRangeException.class)
  public Mono<ResponseEntity<ApiErrorResponse>> handleInvalidDateRange(
      InvalidDateRangeException exception, ServerWebExchange exchange) {
    return errorResponse(
        HttpStatus.BAD_REQUEST,
        "INVALID_DATE_RANGE",
        exception.getMessage(),
        exchange,
        exception,
        false);
  }

  @ExceptionHandler({ServerWebInputException.class, ConstraintViolationException.class})
  public Mono<ResponseEntity<ApiErrorResponse>> handleInvalidRequest(
      Exception exception, ServerWebExchange exchange) {
    return errorResponse(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        requestValidationMessage(exception),
        exchange,
        exception,
        false);
  }

  @ExceptionHandler(InvalidRequestException.class)
  public Mono<ResponseEntity<ApiErrorResponse>> handleInvalidRequest(
      InvalidRequestException exception, ServerWebExchange exchange) {
    return errorResponse(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        exception.getMessage(),
        exchange,
        exception,
        false);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public Mono<ResponseEntity<ApiErrorResponse>> handleResourceNotFound(
      ResourceNotFoundException exception, ServerWebExchange exchange) {
    return errorResponse(
        HttpStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        exception.getMessage(),
        exchange,
        exception,
        false);
  }

  @ExceptionHandler({DatabaseUnavailableException.class, DataAccessException.class})
  public Mono<ResponseEntity<ApiErrorResponse>> handleDatabaseUnavailable(
      Exception exception, ServerWebExchange exchange) {
    return errorResponse(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DATABASE_UNAVAILABLE",
        "The compliance database is temporarily unavailable",
        exchange,
        exception,
        true);
  }

  @ExceptionHandler(Throwable.class)
  public Mono<ResponseEntity<ApiErrorResponse>> handleUnexpectedError(
      Throwable exception, ServerWebExchange exchange) {
    return errorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        GENERIC_ERROR_MESSAGE,
        exchange,
        exception,
        true);
  }

  private Mono<ResponseEntity<ApiErrorResponse>> errorResponse(
      HttpStatus status,
      String code,
      String message,
      ServerWebExchange exchange,
      Throwable exception,
      boolean includeStackTrace) {
    TraceIdentifiers identifiers = traceContextAccessor.current();
    String path = exchange.getRequest().getPath().value();
    if (includeStackTrace) {
      LOGGER.error(
          "Request failed code={} status={} path={} message={}",
          code,
          status.value(),
          path,
          exception.getMessage(),
          exception);
    } else {
      LOGGER.warn(
          "Request rejected code={} status={} path={} message={}",
          code,
          status.value(),
          path,
          exception.getMessage());
    }
    ApiErrorResponse body =
        new ApiErrorResponse(
            OffsetDateTime.now(),
            status.value(),
            status.getReasonPhrase(),
            code,
            message,
            path,
            identifiers.traceId(),
            identifiers.spanId());
    return Mono.just(ResponseEntity.status(status).body(body));
  }

  private String requestValidationMessage(Exception exception) {
    if (exception instanceof ServerWebInputException inputException
        && inputException.getReason() != null) {
      return inputException.getReason();
    }
    return exception.getMessage() == null ? "The request is invalid" : exception.getMessage();
  }
}
