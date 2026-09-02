package com.pharos.compliance.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "common.postgres")
public record PostgresProperties(@NotBlank String url, @NotBlank @DefaultValue("org.postgresql.Driver") String driver,
    @NotBlank String username, @NotBlank String password, @Min(250) @DefaultValue("120000") long connectionTimeout,
    @Min(1) @DefaultValue("10") int maximumPoolSize, @Min(0) @DefaultValue("1") int minimumIdle) {}
