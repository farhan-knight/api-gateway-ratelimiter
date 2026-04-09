package com.example.ratelimiter.service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static reactor.netty.http.HttpConnectionLiveness.log;

public class RedisLeakyBucketService implements RateLimiter{

    private final JedisPool jedisPool;
    private final int maxRequests;
    private final long windowSizeMillis;
    private final double leakRatePerSecond;

    private static final String WATER_KEY_PREFIX = "rate_limiter:leaky_water:";
    private static final String LAST_LEAK_KEY_PREFIX = "rate_limiter:leaky_last_leak:";

    public RedisLeakyBucketService(JedisPool jedisPool, int maxRequests, long windowSizeMillis) {
        this.jedisPool = jedisPool;
        this.maxRequests = maxRequests;
        this.windowSizeMillis = windowSizeMillis;
        this.leakRatePerSecond = 1.0 * maxRequests / windowSizeMillis * 1000;
    }

    @Override
    public boolean isAllowed(String clientId) {

        String waterKey = WATER_KEY_PREFIX + clientId;

        try (Jedis jedis = jedisPool.getResource()) {

            long now = System.currentTimeMillis();
            leakWater(clientId, jedis, now);

            String waterStr = jedis.get(waterKey);
            double currentWater = waterStr != null ? Double.parseDouble(waterStr) : 0;

            if (currentWater >= maxRequests) {
                log.debug("LeakyBucket | client={} | REJECTED | water={}", clientId, currentWater);
                return false;
            }

            double newWater = currentWater + 1;
            jedis.set(waterKey, String.valueOf(newWater));

            log.debug("LeakyBucket | client={} | ALLOWED | water={}", clientId, newWater);
            return true;
        }
    }

    @Override
    public long getCapacity(String clientId) {
        return maxRequests;
    }

    @Override
    public long getAvailableTokens(String clientId) {
        String waterKey = WATER_KEY_PREFIX + clientId;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            leakWater(clientId, jedis, now);
            String waterStr = jedis.get(waterKey);
            double currentWater = waterStr != null ? Double.parseDouble(waterStr) : 0;
            return Math.max(0, (long) (maxRequests - currentWater));
        }
    }

    private void leakWater(String clientId, Jedis jedis, long now) {

        String waterKey = WATER_KEY_PREFIX + clientId;
        String lastLeakKey = LAST_LEAK_KEY_PREFIX + clientId;

        String lastLeakStr = jedis.get(lastLeakKey);

        if (lastLeakStr == null) {
            jedis.set(waterKey, "0");
            jedis.set(lastLeakKey, String.valueOf(now));
            return;
        }

        long lastLeakTime = Long.parseLong(lastLeakStr);
        long elapsedTime = now - lastLeakTime;

        if (elapsedTime <= 0) return;

        // waterToLeak = (elapsedMs * leakRate) / 1000
        double waterToLeak = (elapsedTime * leakRatePerSecond) / 1000.0;
        if (waterToLeak <= 0) return;

        String waterStr = jedis.get(waterKey);
        double currentWater = waterStr != null ? Double.parseDouble(waterStr) : 0;
        double newWater = Math.max(0, currentWater - waterToLeak);

        jedis.set(waterKey, String.valueOf(newWater));
        jedis.set(lastLeakKey, String.valueOf(now));
    }
}
