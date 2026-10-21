package com.fairtix.ratelimit;

import com.fairtix.auth.application.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter}: which key a request is limited under.
 * Uses the real {@link RateLimitService} over a mocked Redisson client, so the
 * assertions are on the Redis key the limiter actually reads.
 *
 * <p>Exposure gate (a), RM:316. The browser client authenticates with the
 * {@code fairtix_token} cookie only, so a limiter that reads just the Bearer
 * header keys every signed-in user by IP.
 *
 * <p>Intended-red commits: {@code 13f00c6} (cookie key), {@code e387b53}
 * (X-Forwarded-For), {@code bc554c0} (fail closed on the auth tier, exemptions,
 * Retry-After).
 */
class RateLimitFilterTest {

    private static final String REMOTE = "198.51.100.9";

    private RedissonClient redisson;
    private RRateLimiter limiter;
    private JwtService jwtService;
    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        RateLimitService service = new RateLimitService(redisson);
        ReflectionTestUtils.setField(service, "authLimit", 10);
        ReflectionTestUtils.setField(service, "ordersLimit", 20);
        ReflectionTestUtils.setField(service, "seatsLimit", 60);
        ReflectionTestUtils.setField(service, "defaultLimit", 100);

        jwtService = mock(JwtService.class);
        filter = new RateLimitFilter(service, jwtService);
        chain = mock(FilterChain.class);
    }

    @Test
    void cookieAuthenticatedRequest_isKeyedByUser() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/orders");
        request.setCookies(new Cookie("fairtix_token", "cookie-jwt"));
        Claims claims = claimsFor("user-1");
        when(jwtService.extractAllClaims("cookie-jwt")).thenReturn(claims);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("rate_limit:user:user-1:/api/orders", limiterKey());
        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void expiredCookie_fallsBackToRemoteAddr() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/orders");
        request.setCookies(new Cookie("fairtix_token", "old-jwt"));
        when(jwtService.extractAllClaims("old-jwt"))
            .thenThrow(new ExpiredJwtException(null, null, "expired"));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("rate_limit:ip:" + REMOTE + ":/api/orders", limiterKey());
    }

    @Test
    void bearerHeader_isKeyedByUser() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/orders");
        request.addHeader("Authorization", "Bearer header-jwt");
        Claims claims = claimsFor("user-2");
        when(jwtService.extractAllClaims("header-jwt")).thenReturn(claims);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("rate_limit:user:user-2:/api/orders", limiterKey());
    }

    /**
     * The filter must not parse X-Forwarded-For itself: a client can put anything in
     * the leftmost entry. Trusted-proxy handling happens before the filter, in
     * Tomcat's RemoteIpValve, which rewrites getRemoteAddr().
     */
    @Test
    void xForwardedFor_isIgnored_keyIsRemoteAddr() throws Exception {
        MockHttpServletRequest request = request("POST", "/auth/login");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("rate_limit:ip:" + REMOTE + ":/auth/login", limiterKey());
    }

    /** Brute-force protection must not disappear when Redis does (RM:316, decision D2). */
    @Test
    void redisError_onAuthTier_failsClosedWith503() throws Exception {
        when(redisson.getRateLimiter(anyString())).thenThrow(new RedisException("connection refused"));
        MockHttpServletRequest request = request("POST", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("\"code\":\"RATE_LIMIT_UNAVAILABLE\""));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void redisError_offAuthTier_failsOpen() throws Exception {
        when(redisson.getRateLimiter(anyString())).thenThrow(new RedisException("connection refused"));
        MockHttpServletRequest request = request("GET", "/api/events");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void rateLimited_returns429WithRetryAfter() throws Exception {
        when(limiter.tryAcquire(1)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/auth/login"), response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        verify(chain, never()).doFilter(any(), any());
    }

    /** A CORS preflight carries no credentials and must not use up the caller's quota. */
    @Test
    void corsPreflight_isNotCounted() throws Exception {
        MockHttpServletRequest request = request("OPTIONS", "/auth/login");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(redisson);
        verify(chain).doFilter(eq(request), any());
    }

    /** Health must answer (503 from the indicators) when Redis is down, not hang in the limiter. */
    @Test
    void actuatorHealth_isNotCounted() throws Exception {
        MockHttpServletRequest request = request("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(redisson);
        verify(chain).doFilter(eq(request), any());
    }

    // ---- Review round 1 on #263 ----

    /**
     * Registration needs no captcha and returns a cookie at once, so a per-account key
     * on the credential endpoints lets one IP mint fresh login buckets.
     */
    @Test
    void credentialEndpoint_withValidCookie_isKeyedByIp() throws Exception {
        MockHttpServletRequest request = request("POST", "/auth/login");
        request.setCookies(new Cookie("fairtix_token", "cookie-jwt"));
        Claims claims = claimsFor("user-1");
        when(jwtService.extractAllClaims("cookie-jwt")).thenReturn(claims);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("rate_limit:ip:" + REMOTE + ":/auth/login", limiterKey());
    }

    /** Spring MVC routes on the decoded path, so the limiter must key on it too. */
    @Test
    void percentEncodedPath_sharesTheDecodedBucket() throws Exception {
        filter.doFilter(request("POST", "/auth/l%6fgin"), new MockHttpServletResponse(), chain);

        assertEquals("rate_limit:ip:" + REMOTE + ":/auth/login", limiterKey());
    }

    @Test
    void percentEncodedCredentialPath_stillFailsClosed() throws Exception {
        when(redisson.getRateLimiter(anyString())).thenThrow(new RedisException("connection refused"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/%61uth/login"), response, chain);

        assertEquals(503, response.getStatus());
    }

    /** Fail closed only where the limit is the brute-force defence; /auth/me hydrates every page. */
    @Test
    void redisError_onAuthMe_failsOpen() throws Exception {
        when(redisson.getRateLimiter(anyString())).thenThrow(new RedisException("connection refused"));
        MockHttpServletRequest request = request("GET", "/auth/me");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void healthLookalikePath_isCounted() throws Exception {
        filter.doFilter(request("GET", "/actuator/healthz"), new MockHttpServletResponse(), chain);

        verify(redisson).getRateLimiter(anyString());
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(REMOTE);
        return request;
    }

    private Claims claimsFor(String userId) {
        Claims claims = mock(Claims.class);
        when(claims.get("userId", String.class)).thenReturn(userId);
        return claims;
    }

    private String limiterKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redisson).getRateLimiter(key.capture());
        return key.getValue();
    }
}
