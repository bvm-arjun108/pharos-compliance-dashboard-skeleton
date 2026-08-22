package com.pharos.compliance.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PostgresProperties.class)
public class DatabaseConfiguration {

  @Bean(destroyMethod = "close")
  DataSource dataSource(PostgresProperties properties) {
    HikariConfig configuration = new HikariConfig();
    configuration.setPoolName("pharos-postgres-pool");
    configuration.setJdbcUrl(properties.url());
    configuration.setDriverClassName(properties.driver());
    configuration.setUsername(properties.username());
    configuration.setPassword(properties.password());
    configuration.setConnectionTimeout(properties.connectionTimeout());
    configuration.setMaximumPoolSize(properties.maximumPoolSize());
    configuration.setMinimumIdle(Math.min(properties.minimumIdle(), properties.maximumPoolSize()));
    return new HikariDataSource(configuration);
  }
}
