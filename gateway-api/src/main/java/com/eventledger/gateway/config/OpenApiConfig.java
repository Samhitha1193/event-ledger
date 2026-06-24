package com.eventledger.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("Event Ledger — Gateway API")
                .version("1.0.0")
                .description("""
                        Public entry point for the Event Ledger system.
                        Accepts financial events, enforces idempotency via SHA-256 fingerprint,
                        and forwards transactions to the Account Service.
                        Proxies balance queries to the Account Service with circuit-breaker protection.
                        """));
    }
}
