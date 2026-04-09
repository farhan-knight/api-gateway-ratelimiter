package com.example.ratelimiter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    private int maxRequests = 10;

    private long windowSizeMillis = 10000;

    private String algorithm = "token-bucket";

    private String apiServerUrl = "http://localhost:8080";


}
