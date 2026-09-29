package com.wu.compliance.dashboard.common.exception;

public class DatabaseUnavailableException extends RuntimeException {
  public DatabaseUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
