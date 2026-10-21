package com.fairtix.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.redisson.api.RateType;
import org.redisson.api.RateIntervalUnit;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for RateLimitService to verify that rate limiting is enforced correctly
 * for different endpoint types. Uses Mockito to mock Redisson rate limiter behavior.
 */
public class RateLimitServiceTest {

    private RedissonClient redissonClient;
    private RRateLimiter rateLimiter;
    private RateLimitService rateLimitService;

    @BeforeEach
    public void setUp() {
        redissonClient = mock(RedissonClient.class);
        rateLimiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        rateLimitService = new RateLimitService(redissonClient);
        // Inject @Value defaults
        ReflectionTestUtils.setField(rateLimitService, "authLimit", 10);
        ReflectionTestUtils.setField(rateLimitService, "ordersLimit", 20);
        ReflectionTestUtils.setField(rateLimitService, "seatsLimit", 60);
        ReflectionTestUtils.setField(rateLimitService, "defaultLimit", 100);
    }

    @ParameterizedTest(name = "{0} → limit {1}")
    @CsvSource({
        "/api/auth/login,           10",
        "/auth/register,            10",
        "/api/admin/users/promote,  20",
        "/api/payments/checkout,    20",
        "/api/events/456/holds,     20",
        "/api/orders/create,        20",
        "/api/holds/abc,            20",
        "/api/events/789/seats,     60",
        "/api/inventory/list,       60",
        "/api/events,               100",
        "/api/other,                100",
    })
    public void isAllowed_enforcesLimitForEndpoint(String url, int limit) {
        Boolean[] answers = new Boolean[limit + 1];
        Arrays.fill(answers, 0, limit, Boolean.TRUE);
        answers[limit] = Boolean.FALSE;
        Boolean[] rest = Arrays.copyOfRange(answers, 1, answers.length);

        when(rateLimiter.isExists()).thenReturn(false);
        when(rateLimiter.trySetRate(eq(RateType.OVERALL), eq((long) limit), eq(1L), eq(RateIntervalUnit.MINUTES))).thenReturn(true);
        when(rateLimiter.tryAcquire(1)).thenReturn(answers[0], rest);

        for (int i = 0; i < limit; i++) {
            assertTrue(rateLimitService.isAllowed("1.2.3.4", url));
        }
        assertFalse(rateLimitService.isAllowed("1.2.3.4", url));
    }

    @Test
    public void testRateLimitByUserIdAndIp() {
        String userKey = "user:11111111-1111-1111-1111-111111111111";
        String ipKey = "ip:1.2.3.4";
        String url = "/api/events";

        when(rateLimiter.isExists()).thenReturn(false);
        when(rateLimiter.trySetRate(any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(rateLimiter.tryAcquire(1)).thenReturn(true, false);
        assertTrue(rateLimitService.isAllowed(userKey, url));
        assertFalse(rateLimitService.isAllowed(userKey, url));

        when(rateLimiter.isExists()).thenReturn(false);
        when(rateLimiter.trySetRate(any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(rateLimiter.tryAcquire(1)).thenReturn(true, false);
        assertTrue(rateLimitService.isAllowed(ipKey, url));
        assertFalse(rateLimitService.isAllowed(ipKey, url));
    }

    /**
     * The keys expire, so a limiter that existed at isExists() can be gone by
     * tryAcquire(), which then throws "RateLimiter is not initialized" (a 503 on the
     * credential endpoints). trySetRate is set-if-absent: call it every time.
     * Review round 2 on #263.
     */
    @Test
    public void isAllowed_initialisesTheRateEvenWhenTheLimiterExists() {
        when(rateLimiter.isExists()).thenReturn(true);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);

        rateLimitService.isAllowed("ip:1.2.3.4", "/auth/login");

        verify(rateLimiter).trySetRate(RateType.OVERALL, 10L, 1L, RateIntervalUnit.MINUTES);
    }

    @Test
    public void testResolveLimitCoversAllEndpointCategories() {
        assertEquals(10, rateLimitService.resolveLimit("/api/auth/login"));
        assertEquals(10, rateLimitService.resolveLimit("/auth/register"));
        assertEquals(20, rateLimitService.resolveLimit("/api/payments/checkout"));
        assertEquals(20, rateLimitService.resolveLimit("/api/orders"));
        assertEquals(20, rateLimitService.resolveLimit("/api/admin/users"));
        assertEquals(20, rateLimitService.resolveLimit("/api/events/{id}/holds"));
        assertEquals(60, rateLimitService.resolveLimit("/api/events/{id}/seats"));
        assertEquals(60, rateLimitService.resolveLimit("/api/inventory/list"));
        assertEquals(100, rateLimitService.resolveLimit("/api/events"));
        assertEquals(100, rateLimitService.resolveLimit("/api/tickets"));
        assertEquals(100, rateLimitService.resolveLimit("/api/analytics/dashboard"));
    }
}
