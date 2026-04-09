package com.example.ratelimiter.service;

public interface RateLimiter {

    boolean isAllowed(String clientId);

    long getCapacity(String clientId);

    long getAvailableTokens(String clientId);

}
