package com.fairtix.ratelimit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.redisson.api.RateType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.Set;

@Service
public class RateLimitService {

    private static final Set<String> CREDENTIAL_ENDPOINTS = Set.of(
        "/auth/login",
        "/auth/register",
        "/auth/verify",
        "/auth/resend-verification",
        "/auth/forgot-password",
        "/auth/reset-password",
        "/auth/step-up/verify");

    private static final Set<String> STRIPE_WEBHOOK_PATHS = Set.of(
        "/api/webhooks/stripe",
        "/api/webhooks/stripe-connect");

    // Longer than the one-minute window, so an active bucket never resets early. The
    // key includes the path, which the caller chooses, so it must expire.
    private static final Duration KEY_TTL = Duration.ofMinutes(2);

    // RedissonRateLimiter's tryAcquire script error when the rate hash is missing.
    private static final String NOT_INITIALIZED = "RateLimiter is not initialized";

    private final RedissonClient redissonClient;

    @Value("${ratelimit.auth:10}")
    private int authLimit;

    @Value("${ratelimit.orders:20}")
    private int ordersLimit;

    @Value("${ratelimit.seats:60}")
    private int seatsLimit;

    @Value("${ratelimit.default:100}")
    private int defaultLimit;

    @Value("${ratelimit.webhooks:1000}")
    private int webhooksLimit;

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
        // The limit is part of the key: trySetRate below never overwrites a stored rate,
        // and every request renews the TTL, so a busy bucket would otherwise keep its old
        // rate after a tier changes. Side effect: during a rolling deploy that changes the
        // key, old and new replicas keep separate buckets, so for the overlap the per-IP
        // ceiling is the old limit plus the new one (the per-account login lockout is a
        // separate counter).
        int limit = resolveLimit(url);
        String redisKey = "rate_limit:" + key + ":" + url + ":limit=" + limit;

        RRateLimiter limiter = redissonClient.getRateLimiter(redisKey);

        // trySetRate is set-if-absent, so it runs on every call. The key can still
        // expire in the round trip before tryAcquire, which then throws "not
        // initialized": set the rate again and retry once.
        limiter.trySetRate(RateType.OVERALL, limit, Duration.ofMinutes(1));
        boolean allowed;
        try {
            allowed = limiter.tryAcquire(1);
        } catch (RedisException e) {
            if (e.getMessage() == null || !e.getMessage().contains(NOT_INITIALIZED)) {
                throw e;
            }
            limiter.trySetRate(RateType.OVERALL, limit, Duration.ofMinutes(1));
            allowed = limiter.tryAcquire(1);
        }
        limiter.expire(KEY_TTL);
        return allowed;
    }

    /**
     * Returns the per-minute rate limit for the given URL.
     */
    int resolveLimit(String url) {
        // Stripe sends no cookie, so webhooks are keyed by IP, and a handful of Stripe
        // IPs carry every platform and connected-account event: the default tier would
        // 429 a busy on-sale into Stripe's hours-long retry backoff. Still limited, since
        // the bucket is checked before the signature. The two exact paths only, so a
        // future route under /api/webhooks/ doesn't inherit this limit unreviewed.
        if (isStripeWebhook(url)) {
            return webhooksLimit;
        }
        // Refresh takes a high-entropy cookie, and a failed refresh signs the SPA user
        // out; after the access cookie expires it's keyed by IP, so 10/min would sign
        // out users who share a NAT.
        if (url.equals("/auth/refresh")) {
            return defaultLimit;
        }
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

    public boolean isStripeWebhook(String url) {
        return STRIPE_WEBHOOK_PATHS.contains(url);
    }

    boolean isAuthTier(String url) {
        return url.startsWith("/api/auth") || url.startsWith("/auth");
    }

    /**
     * The endpoints that take or mint a credential, whose limit is the brute-force
     * defence: the filter keys them by IP only and fails closed on them. Not /auth/me
     * or /auth/logout, which every signed-in page needs, and not /auth/refresh: it
     * takes a high-entropy refresh cookie, and the SPA signs the user out on any
     * refresh failure.
     */
    public boolean isCredentialEndpoint(String url) {
        return CREDENTIAL_ENDPOINTS.contains(url);
    }
}
