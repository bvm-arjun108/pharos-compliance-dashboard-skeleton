package com.pharos.compliance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Guards the one contract in this codebase that nothing else checks: the binding between a native
 * query's column aliases and the getters on the Spring Data projection interface it is returned as.
 *
 * <p>That binding is resolved reflectively at runtime by name. Rename an alias without renaming its
 * getter (or vice versa) and the code still compiles, the query still runs, the endpoint still
 * returns HTTP 200 — and the affected field is silently null on every row while every other field
 * looks correct. In a compliance report that reads as "this transaction has no sender name" rather
 * than "the application is broken", which is exactly the kind of defect that survives review.
 *
 * <p>This test needs no Spring context and no database: it reads each repository method's {@code
 * @Query} SQL and asserts every getter on that method's projection has a matching {@code AS
 * "alias"} in it. Cheap enough to run on every build, and it fails at the point the mistake is made
 * instead of in production.
 *
 * <p>It deliberately checks each method's own query rather than a pool of all SQL, so an alias that
 * exists in some *other* query does not mask a missing one here.
 */
class ProjectionColumnBindingTest {

  private static final List<String> REPOSITORIES =
      List.of(
          "com.pharos.compliance.transaction.repository.TransactionReportRepository",
          "com.pharos.compliance.dashboard.repository.DashboardRepository",
          "com.pharos.compliance.batch.repository.BatchExplorerRepository",
          "com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository");

  @Test
  @DisplayName("every projection getter has a matching column alias in its own query")
  void projectionGettersMatchQueryAliases() throws Exception {
    List<String> failures = new ArrayList<>();
    int checkedGetters = 0;
    int checkedMethods = 0;

    for (String repositoryName : REPOSITORIES) {
      Class<?> repository = Class.forName(repositoryName);
      for (Method method : repository.getDeclaredMethods()) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
          continue;
        }
        Class<?> projection = projectionType(method.getGenericReturnType());
        if (projection == null || !projection.isInterface()) {
          continue;
        }
        checkedMethods++;
        String sql = query.value();
        for (String alias : expectedAliases(projection)) {
          checkedGetters++;
          if (!sql.contains("\"" + alias + "\"")) {
            failures.add(
                String.format(
                    "%s.%s returns %s, whose get%s%s() has no matching AS \"%s\" in its query",
                    repository.getSimpleName(),
                    method.getName(),
                    projection.getSimpleName(),
                    alias.substring(0, 1).toUpperCase(),
                    alias.substring(1),
                    alias));
          }
        }
      }
    }

    assertTrue(
        checkedMethods > 0 && checkedGetters > 0,
        "guard matched nothing — the repositories or their projections were probably renamed,"
            + " which would leave this test silently passing while checking nothing");
    assertTrue(
        failures.isEmpty(),
        "Projection getters with no matching column alias:\n  " + String.join("\n  ", failures));
  }

  /** Unwraps {@code List<X>} / {@code Optional<X>} / {@code X}; null for non-projection returns. */
  private static Class<?> projectionType(Type returnType) {
    if (returnType instanceof ParameterizedType parameterized) {
      Type raw = parameterized.getRawType();
      if (raw.equals(List.class) || raw.equals(Optional.class)) {
        Type argument = parameterized.getActualTypeArguments()[0];
        return argument instanceof Class<?> clazz ? clazz : null;
      }
      return null;
    }
    return returnType instanceof Class<?> clazz && !clazz.isPrimitive() ? clazz : null;
  }

  /** {@code getSenderName()} -> {@code senderName}; {@code isActive()} -> {@code active}. */
  private static Set<String> expectedAliases(Class<?> projection) {
    Set<String> aliases = new LinkedHashSet<>();
    for (Method getter : projection.getMethods()) {
      if (getter.getParameterCount() != 0) {
        continue;
      }
      String name = getter.getName();
      String property;
      if (name.startsWith("get") && name.length() > 3) {
        property = name.substring(3);
      } else if (name.startsWith("is") && name.length() > 2) {
        property = name.substring(2);
      } else {
        continue;
      }
      aliases.add(property.substring(0, 1).toLowerCase() + property.substring(1));
    }
    return aliases;
  }
}
