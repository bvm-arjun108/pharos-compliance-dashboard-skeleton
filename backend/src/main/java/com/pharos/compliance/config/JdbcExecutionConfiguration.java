package com.pharos.compliance.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
public class JdbcExecutionConfiguration {

  @Bean(name = "jdbcScheduler", destroyMethod = "dispose")
  Scheduler jdbcScheduler() {
    ExecutorService executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pharos-jdbc-", 0).factory());
    return Schedulers.fromExecutorService(executor);
  }
}
