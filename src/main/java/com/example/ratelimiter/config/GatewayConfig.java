package com.example.ratelimiter.config;

import com.example.ratelimiter.filter.RateLimiterFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    private final RateLimiterProperties rateLimiterProperties;
    private final RateLimiterFilter rateLimiterFilter;

    public GatewayConfig(RateLimiterProperties rateLimiterProperties,
                         RateLimiterFilter rateLimiterFilter) {
        this.rateLimiterProperties = rateLimiterProperties;
        this.rateLimiterFilter = rateLimiterFilter;
    }

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("api-route", r -> r
                        .path("/api/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .filter(rateLimiterFilter.apply(new RateLimiterFilter.Config()))
                        )
                        .uri(rateLimiterProperties.getApiServerUrl()))
                .build();
    }
}