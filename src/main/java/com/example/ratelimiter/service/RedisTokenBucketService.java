package com.example.ratelimiter.service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static reactor.netty.http.HttpConnectionLiveness.log;

public class RedisTokenBucketService implements RateLimiter{

    private final JedisPool jedisPool;
    private final int maxRequests;
    private final long windowSizeMillis;
    private final double refillRatePerSecond;

    private static final String TOKENS_KEY_PREFIX = "rate_limiter:tokens:";
    private static final String LAST_REFILL_KEY_PREFIX = "rate_limiter:last_refill:";

    public RedisTokenBucketService(JedisPool jedisPool, int maxRequests, long windowSizeMillis) {
        this.jedisPool = jedisPool;
        this.maxRequests = maxRequests;
        this.windowSizeMillis = windowSizeMillis;
        this.refillRatePerSecond = 1.0 * maxRequests / windowSizeMillis * 1000;
    }

    @Override
    public boolean isAllowed(String clientId) {

        String tokenKey = TOKENS_KEY_PREFIX + clientId;

        try (Jedis jedis = jedisPool.getResource()) {

            refillTokens(clientId, jedis);

            String tokenStr = jedis.get(tokenKey);
            long currentTokens = tokenStr != null ? Long.parseLong(tokenStr) : maxRequests;

            if (currentTokens <= 0) {
                log.debug("TokenBucket | client={} | REJECTED | tokens=0", clientId);
                return false;
            }

            long decremented = jedis.decr(tokenKey);
            boolean allowed = decremented >= 0;

            log.debug("TokenBucket | client={} | {} | tokens={}",
                    clientId, allowed ? "ALLOWED" : "REJECTED", decremented);
            return allowed;
        }
    }

    @Override
    public long getCapacity(String clientId) {
        return maxRequests;
    }

    @Override
    public long getAvailableTokens(String clientId) {
        String tokenKey = TOKENS_KEY_PREFIX + clientId;
        try (Jedis jedis = jedisPool.getResource()) {
            refillTokens(clientId, jedis);
            String tokenStr = jedis.get(tokenKey);
            return tokenStr != null ? Math.max(0, Long.parseLong(tokenStr)) : maxRequests;
        }
    }

    private void refillTokens(String clientId, Jedis jedis) {

        String tokensKey = TOKENS_KEY_PREFIX + clientId;
        String lastRefillKey = LAST_REFILL_KEY_PREFIX + clientId;

        long now = System.currentTimeMillis();
        String lastRefillStr = jedis.get(lastRefillKey);

        if (lastRefillStr == null) {
            jedis.set(tokensKey, String.valueOf(maxRequests));
            jedis.set(lastRefillKey, String.valueOf(now));
            return;
        }

        long lastRefillTime = Long.parseLong(lastRefillStr);
        long elapsedTime = now - lastRefillTime;

        if (elapsedTime <= 0) return;

        // tokensToAdd = (elapsedMs * refillRate) / 1000
        long tokensToAdd = (long) ((elapsedTime * refillRatePerSecond) / 1000);
        if (tokensToAdd <= 0) return;

        String tokenStr = jedis.get(tokensKey);
        long currentTokens = tokenStr != null ? Long.parseLong(tokenStr) : maxRequests;
        long newTokens = Math.min(maxRequests, currentTokens + tokensToAdd);

        jedis.set(tokensKey, String.valueOf(newTokens));
        jedis.set(lastRefillKey, String.valueOf(now));
    }
}
