package com.wu.compliance.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.wu.compliance.dashboard.batch.controller.BatchExplorerController;
import com.wu.compliance.dashboard.batch.service.BatchExplorerService;
import com.wu.compliance.dashboard.dashboard.controller.DashboardController;
import com.wu.compliance.dashboard.dashboard.service.DashboardService;
import com.wu.compliance.dashboard.health.controller.HealthController;
import com.wu.compliance.dashboard.health.service.HealthService;
import com.wu.compliance.dashboard.reportgroup.controller.ReportConfigController;
import com.wu.compliance.dashboard.reportgroup.service.ReportConfigService;
import com.wu.compliance.dashboard.transaction.controller.TransactionReportController;
import com.wu.compliance.dashboard.transaction.service.TransactionReportService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The controllers are thin: each endpoint forwards its arguments to one service call. This invokes
 * every endpoint method with default arguments against a mocked service and checks that exactly one
 * service call was made, which keeps a controller from silently dropping or double-calling.
 */
class ControllerDelegationTest {
  @Test
  void batchExplorerController() throws Exception {
    BatchExplorerService service = mock(BatchExplorerService.class);
    invokeEveryEndpoint(new BatchExplorerController(service), service);
  }

  @Test
  void dashboardController() throws Exception {
    DashboardService service = mock(DashboardService.class);
    invokeEveryEndpoint(new DashboardController(service), service);
  }

  @Test
  void healthController() throws Exception {
    HealthService service = mock(HealthService.class);
    invokeEveryEndpoint(new HealthController(service), service);
  }

  @Test
  void reportConfigController() throws Exception {
    ReportConfigService service = mock(ReportConfigService.class);
    invokeEveryEndpoint(new ReportConfigController(service), service);
  }

  @Test
  void transactionReportController() throws Exception {
    TransactionReportService service = mock(TransactionReportService.class);
    invokeEveryEndpoint(new TransactionReportController(service), service);
  }

  private static void invokeEveryEndpoint(Object controller, Object service) throws Exception {
    List<Method> endpoints = Stream
      .of(controller.getClass().getDeclaredMethods())
      .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
      .toList();
    assertThat(endpoints).isNotEmpty();
    for (Method endpoint : endpoints) {
      Mockito.clearInvocations(service);
      try {
        endpoint.invoke(controller, Arrays.stream(endpoint.getParameterTypes()).map(ControllerDelegationTest::defaultFor).toArray());
      } catch (InvocationTargetException e) {
        throw new AssertionError(endpoint.getName() + " threw " + e.getCause(), e.getCause());
      }
      assertThat(Mockito
        .mockingDetails(service)
        .getInvocations()
        .stream()
        .filter(i -> !i.getMethod().getName().equals("toString")))
        .as(endpoint.getName())
        .hasSize(1);
    }
  }

  private static Object defaultFor(Class<?> type) {
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == boolean.class) {
      return false;
    }
    return null;
  }
}
