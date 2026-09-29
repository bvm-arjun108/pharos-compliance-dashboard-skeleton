package com.wu.compliance.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ComplianceDashboardApplication {
  public static void main(String[] args) {
    SpringApplication.run(ComplianceDashboardApplication.class, args);
  }
}
