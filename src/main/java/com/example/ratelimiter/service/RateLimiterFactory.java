package com.example.ratelimiter.service;

import redis.clients.jedis.JedisPool;

public class RateLimiterFactory {

    public static RateLimiter createRateLimiter(String type, JedisPool jedisPool,
                                                int maxRequests, long windowSizeMillis) {
        return switch (type) {
            case "fixed" ->
                    new RedisFixedWindowService(jedisPool, maxRequests, windowSizeMillis);
            case "sliding" ->
                    new RedisSlidingWindowService(jedisPool, maxRequests, windowSizeMillis);
            case "token-bucket" ->
                    new RedisTokenBucketService(jedisPool, maxRequests, windowSizeMillis);
            case "leaky-bucket" ->
                    new RedisLeakyBucketService(jedisPool, maxRequests, windowSizeMillis);
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }
}