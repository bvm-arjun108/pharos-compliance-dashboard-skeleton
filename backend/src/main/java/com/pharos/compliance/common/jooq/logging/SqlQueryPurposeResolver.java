package com.pharos.compliance.common.jooq.logging;

import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class SqlQueryPurposeResolver {
  static final String UNCLASSIFIED_QUERY = "Unclassified jOOQ query";
  private static final String APPLICATION_PACKAGE = "com.pharos.compliance.";
  private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private static final Map<MethodKey, Optional<String>> PURPOSES = new ConcurrentHashMap<>();

  private SqlQueryPurposeResolver() {
  }

  static String resolve() {
    return STACK_WALKER.walk(frames -> frames
      .filter(frame -> frame.getClassName().startsWith(APPLICATION_PACKAGE))
      .map(SqlQueryPurposeResolver::purposeFor)
      .flatMap(Optional::stream)
      .findFirst()
      .orElse(UNCLASSIFIED_QUERY));
  }

  private static Optional<String> purposeFor(StackFrame frame) {
    MethodKey key = new MethodKey(frame.getDeclaringClass(), frame.getMethodName());
    return PURPOSES.computeIfAbsent(key, SqlQueryPurposeResolver::findPurpose);
  }

  private static Optional<String> findPurpose(MethodKey key) {
    return Arrays
      .stream(key.declaringClass().getDeclaredMethods())
      .filter(method -> method.getName().equals(key.methodName()))
      .map(Method::getDeclaredAnnotations)
      .flatMap(Arrays::stream)
      .filter(SqlQueryPurpose.class::isInstance)
      .map(SqlQueryPurpose.class::cast)
      .map(SqlQueryPurpose::value)
      .findFirst();
  }

  private record MethodKey(Class<?> declaringClass, String methodName) {}
}
