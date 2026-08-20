package com.example.heimdall.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heimdallOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Heimdall Gateway API")
                        .description("Consistent-hash-routed, multi-replica video streaming and load-balancing API. "
                                + "Reads support HTTP Range requests for seeking; uploads/deletes require an X-API-Key header.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")));
    }
}
