package com.fairtix.ratelimit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.redisson.api.RateType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.redisson.api.RateIntervalUnit;

@Service
public class RateLimitService {

    private final RedissonClient redissonClient;

    @Value("${ratelimit.auth:10}")
    private int authLimit;

    @Value("${ratelimit.orders:20}")
    private int ordersLimit;

    @Value("${ratelimit.seats:60}")
    private int seatsLimit;

    @Value("${ratelimit.default:100}")
    private int defaultLimit;

    public RateLimitService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Checks if a request from a given key (userId or IP) to a specific endpoint is allowed based on the rate limit.
     * @param key the userId or IP address of the client making the request
     * @param url the URL being accessed
     * @return true if the request is allowed, false if it exceeds the rate limit
     */
    public boolean isAllowed(String key, String url) {
        String redisKey = "rate_limit:" + key + ":" + url;

        RRateLimiter limiter = redissonClient.getRateLimiter(redisKey);

        if (!limiter.isExists()) {
            int limit = resolveLimit(url);
            limiter.trySetRate(RateType.OVERALL, limit, 1, RateIntervalUnit.MINUTES);
        }
        return limiter.tryAcquire(1);
    }

    /**
     * Returns the per-minute rate limit for the given URL.
     */
    int resolveLimit(String url) {
        if (isAuthTier(url)) {
            return authLimit;
        }
        if (url.startsWith("/api/payments")) {
            return ordersLimit;
        }
        if (url.startsWith("/api/orders") || url.contains("/holds") || url.startsWith("/api/admin")) {
            return ordersLimit;
        }
        if (url.startsWith("/api/inventory") || url.contains("/seats")) {
            return seatsLimit;
        }
        return defaultLimit;
    }

    /**
     * Login, registration, password reset, refresh and step-up: the endpoints whose
     * limit is the brute-force defence, so the limiter fails closed on them.
     */
    public boolean isAuthTier(String url) {
        return url.startsWith("/api/auth") || url.startsWith("/auth");
    }
}
