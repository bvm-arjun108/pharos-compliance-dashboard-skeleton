package com.wu.compliance.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.wu.compliance.dashboard.config.ConfigurationBeansTest;
import org.junit.jupiter.api.Test;

class ComplianceDashboardApplicationTest {
  @Test
  void mainStartsTheApplicationContext() {
    // no web server and a fake JDBC driver: the context wires up without Tomcat or PostgreSQL
    String[] args = {"--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--common.postgres.url=jdbc:fake:main",
        "--common.postgres.driver=" + ConfigurationBeansTest.FakeDriver.class.getName(),
        "--common.postgres.username=u", "--common.postgres.password=p"};
    assertThatCode(() -> ComplianceDashboardApplication.main(args)).doesNotThrowAnyException();
    assertThat(new ComplianceDashboardApplication()).isNotNull();
  }

  @Test
  void mainFailsFastWhenThePostgresPoolCannotStart() {
    String[] args = {"--spring.main.web-application-type=none", "--common.postgres.url=jdbc:postgresql://127.0.0.1:1/none",
        "--common.postgres.username=u",
        "--common.postgres.password=p", "--common.postgres.connection-timeout=250"};
    assertThatThrownBy(() -> ComplianceDashboardApplication.main(args)).isInstanceOf(RuntimeException.class);
  }
}
