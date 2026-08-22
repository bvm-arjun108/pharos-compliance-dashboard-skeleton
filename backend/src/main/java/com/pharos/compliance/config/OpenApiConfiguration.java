package com.pharos.compliance.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  @Bean
  OpenAPI pharosOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Pharos Compliance Operations API")
                .description("Reactive APIs for the Phase 1 compliance operations dashboard.")
                .version("v1")
                .contact(new Contact().name("Pharos Compliance Engineering")));
  }
}
