package com.fairtix.ratelimit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.redisson.api.RateType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.redisson.api.RateIntervalUnit;

import java.time.Duration;
import java.util.Set;

@Service
public class RateLimitService {

    private static final Set<String> CREDENTIAL_ENDPOINTS = Set.of(
        "/auth/login",
        "/auth/register",
        "/auth/refresh",
        "/auth/verify",
        "/auth/resend-verification",
        "/auth/forgot-password",
        "/auth/reset-password",
        "/auth/step-up/verify");

    // Longer than the one-minute window, so an active bucket never resets early. The
    // key includes the path, which the caller chooses, so it must expire.
    private static final Duration KEY_TTL = Duration.ofMinutes(2);

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
        boolean allowed = limiter.tryAcquire(1);
        limiter.expire(KEY_TTL);
        return allowed;
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

    boolean isAuthTier(String url) {
        return url.startsWith("/api/auth") || url.startsWith("/auth");
    }

    /**
     * The endpoints that take or mint a credential, whose limit is the brute-force
     * defence: the filter keys them by IP only and fails closed on them. Not /auth/me
     * or /auth/logout, which every signed-in page needs.
     */
    public boolean isCredentialEndpoint(String url) {
        return CREDENTIAL_ENDPOINTS.contains(url);
    }
}
