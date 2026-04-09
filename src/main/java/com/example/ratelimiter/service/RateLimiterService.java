package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimiterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final JedisPool jedisPool;
    private final RateLimiterProperties properties;
    private volatile RateLimiter activeRateLimiter;
    private volatile String currentAlgorithm;

    public RateLimiterService(JedisPool jedisPool, RateLimiterProperties properties) {
        this.jedisPool = jedisPool;
        this.properties = properties;
        this.currentAlgorithm = properties.getAlgorithm();

        this.activeRateLimiter = RateLimiterFactory.createRateLimiter(
                currentAlgorithm,
                jedisPool,
                properties.getMaxRequests(),
                properties.getWindowSizeMillis()
        );

        log.info("RateLimiterService started | algo={} | maxRequests={} | windowSizeMillis={}",
                currentAlgorithm,
                properties.getMaxRequests(),
                properties.getWindowSizeMillis());
    }

    public boolean isAllowed(String clientId) {
        boolean allowed = activeRateLimiter.isAllowed(clientId);
        log.info("client={} | algo={} | allowed={}", clientId, currentAlgorithm, allowed);
        return allowed;
    }

    public long getCapacity(String clientId) {
        return activeRateLimiter.getCapacity(clientId);
    }

    public long getAvailableTokens(String clientId) {
        return activeRateLimiter.getAvailableTokens(clientId);
    }

    public String getActiveAlgorithm() {
        return currentAlgorithm;
    }

    public void switchAlgorithm(String newAlgorithm) {
        this.activeRateLimiter = RateLimiterFactory.createRateLimiter(
                newAlgorithm, jedisPool,
                properties.getMaxRequests(), properties.getWindowSizeMillis()
        );
        this.currentAlgorithm = newAlgorithm;
        log.info("Switched algorithm to: {}", newAlgorithm);
    }

    public void updateConfig(String algorithm, int maxRequests, long windowSizeMillis) {
        this.activeRateLimiter = RateLimiterFactory.createRateLimiter(
                algorithm, jedisPool, maxRequests, windowSizeMillis
        );
        this.currentAlgorithm = algorithm;
        log.info("Updated config | algo={} | maxRequests={} | windowSizeMillis={}",
                algorithm, maxRequests, windowSizeMillis);
    }
}