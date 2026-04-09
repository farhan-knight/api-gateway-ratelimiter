package com.example.ratelimiter.service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static reactor.netty.http.HttpConnectionLiveness.log;

public class RedisFixedWindowService implements RateLimiter{

    private final JedisPool jedisPool;
    private final int maxRequests;
    private final long windowSizeMillis;

    private static final String COUNT_KEY_PREFIX = "rate_limiter:fixed_count:";
    private static final String WINDOW_START_KEY_PREFIX = "rate_limiter:fixed_window_start:";

    public RedisFixedWindowService(JedisPool jedisPool, int maxRequests, long windowSizeMillis) {
        this.jedisPool = jedisPool;
        this.maxRequests = maxRequests;
        this.windowSizeMillis = windowSizeMillis;
    }

    @Override
    public boolean isAllowed(String clientId) {

        String countKey = COUNT_KEY_PREFIX + clientId;

        try (Jedis jedis = jedisPool.getResource()) {

            long now = System.currentTimeMillis();
            resetWindowIfExpired(clientId, jedis, now);

            String countStr = jedis.get(countKey);
            long currentCount = countStr != null ? Long.parseLong(countStr) : 0;

            if (currentCount >= maxRequests) {
                log.debug("FixedWindow | client={} | REJECTED | count={}", clientId, currentCount);
                return false;
            }

            long newCount = jedis.incr(countKey);
            boolean allowed = newCount <= maxRequests;

            log.debug("FixedWindow | client={} | {} | count={}",
                    clientId, allowed ? "ALLOWED" : "REJECTED", newCount);
            return allowed;
        }
    }

    @Override
    public long getCapacity(String clientId) {
        return maxRequests;
    }

    @Override
    public long getAvailableTokens(String clientId) {
        String countKey = COUNT_KEY_PREFIX + clientId;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            resetWindowIfExpired(clientId, jedis, now);
            String countStr = jedis.get(countKey);
            long currentCount = countStr != null ? Long.parseLong(countStr) : 0;
            return Math.max(0, maxRequests - currentCount);
        }
    }

    private void resetWindowIfExpired(String clientId, Jedis jedis, long now) {

        String countKey = COUNT_KEY_PREFIX + clientId;
        String windowStartKey = WINDOW_START_KEY_PREFIX + clientId;

        String windowStartStr = jedis.get(windowStartKey);


        if (windowStartStr == null) {
            jedis.set(windowStartKey, String.valueOf(now));
            jedis.set(countKey, "0");
            return;
        }

        long windowStart = Long.parseLong(windowStartStr);
        long elapsedTime = now - windowStart;


        if (elapsedTime >= windowSizeMillis) {
            jedis.set(windowStartKey, String.valueOf(now));
            jedis.set(countKey, "0");
            log.debug("FixedWindow | client={} | Window reset", clientId);
        }
    }

}
