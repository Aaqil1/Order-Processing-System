package com.peerislands.orders.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderProcessingOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Order Processing System API")
                .description("Create, retrieve, list, update status, and cancel orders. "
                        + "Status changes are governed by a centralized state machine.")
                .version("v1")
                .license(new License().name("MIT")));
    }
}
