package com.example.heimdall.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Opens up the API to the standalone dashboard frontend (served from its own
 * origin, e.g. a plain static file server on a different port). Wide open
 * ("*") because this is a demo project with no cookie-based session to
 * protect - a real deployment would restrict this to the dashboard's actual
 * origin.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/objects/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*");
        registry.addMapping("/cluster/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET");
        registry.addMapping("/metrics/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET");
    }
}
