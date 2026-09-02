package com.pharos.compliance.common.jooq.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes the business purpose of an application operation that executes SQL.
 *
 * <p>The SQL listener resolves this value from the active call stack so every formatted query
 * starts with an operator-friendly explanation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SqlQueryPurpose {
  String value();
}
