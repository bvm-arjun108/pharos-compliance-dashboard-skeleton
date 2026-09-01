package com.pharos.compliance.common.error;

import com.pharos.compliance.common.exception.DatabaseUnavailableException;
import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.common.tracing.TraceContextAccessor;
import com.pharos.compliance.common.tracing.TraceContextAccessor.TraceIdentifiers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
  public ResponseEntity<ApiErrorResponse> handleInvalidDateRange(
      InvalidDateRangeException exception, HttpServletRequest request) {
    return errorResponse(
        HttpStatus.BAD_REQUEST,
        "INVALID_DATE_RANGE",
        exception.getMessage(),
        request,
        exception,
        false);
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    ConstraintViolationException.class
  })
  public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
      Exception exception, HttpServletRequest request) {
    return errorResponse(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        requestValidationMessage(exception),
        request,
        exception,
        false);
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
      InvalidRequestException exception, HttpServletRequest request) {
    return errorResponse(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        exception.getMessage(),
        request,
        exception,
        false);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
      ResourceNotFoundException exception, HttpServletRequest request) {
    return errorResponse(
        HttpStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        exception.getMessage(),
        request,
        exception,
        false);
  }

  @ExceptionHandler({DatabaseUnavailableException.class, DataAccessException.class})
  public ResponseEntity<ApiErrorResponse> handleDatabaseUnavailable(
      Exception exception, HttpServletRequest request) {
    return errorResponse(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DATABASE_UNAVAILABLE",
        "The compliance database is temporarily unavailable",
        request,
        exception,
        true);
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpectedError(
      Throwable exception, HttpServletRequest request) {
    return errorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        GENERIC_ERROR_MESSAGE,
        request,
        exception,
        true);
  }

  private ResponseEntity<ApiErrorResponse> errorResponse(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest request,
      Throwable exception,
      boolean includeStackTrace) {
    TraceIdentifiers identifiers = traceContextAccessor.current();
    String path = request.getRequestURI();
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
    return ResponseEntity.status(status).body(body);
  }

  private String requestValidationMessage(Exception exception) {
    if (exception instanceof MethodArgumentTypeMismatchException typeMismatch) {
      return "Invalid value '"
          + typeMismatch.getValue()
          + "' for parameter '"
          + typeMismatch.getName()
          + "'";
    }
    return exception.getMessage() == null ? "The request is invalid" : exception.getMessage();
  }
}
