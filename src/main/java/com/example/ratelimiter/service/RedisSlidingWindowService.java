package com.example.ratelimiter.service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.UUID;

import static reactor.netty.http.HttpConnectionLiveness.log;

public class RedisSlidingWindowService implements RateLimiter{

    private final JedisPool jedisPool;
    private final int maxRequests;
    private final long windowSizeMillis;

    private static final String SLIDING_KEY_PREFIX = "rate_limiter:sliding:";

    public RedisSlidingWindowService(JedisPool jedisPool, int maxRequests, long windowSizeMillis) {
        this.jedisPool = jedisPool;
        this.maxRequests = maxRequests;
        this.windowSizeMillis = windowSizeMillis;
    }

    @Override
    public boolean isAllowed(String clientId) {

        String key = SLIDING_KEY_PREFIX + clientId;

        try (Jedis jedis = jedisPool.getResource()) {

            long now = System.currentTimeMillis();
            long windowStart = now - windowSizeMillis;

            jedis.zremrangeByScore(key, "-inf", String.valueOf(windowStart));

            long currentCount = jedis.zcard(key);

            if (currentCount >= maxRequests) {
                log.debug("SlidingWindow | client={} | REJECTED | count={}", clientId, currentCount);
                return false;
            }

            String member = now + ":" + UUID.randomUUID();
            jedis.zadd(key, now, member);

            long ttlSeconds = (windowSizeMillis / 1000) + 1;
            jedis.expire(key, ttlSeconds);

            log.debug("SlidingWindow | client={} | ALLOWED | count={}", clientId, currentCount + 1);
            return true;
        }
    }

    @Override
    public long getCapacity(String clientId) {
        return maxRequests;
    }

    @Override
    public long getAvailableTokens(String clientId) {
        String key = SLIDING_KEY_PREFIX + clientId;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            jedis.zremrangeByScore(key, "-inf", String.valueOf(now - windowSizeMillis));
            long currentCount = jedis.zcard(key);
            return Math.max(0, maxRequests - currentCount);
        }
    }

}
