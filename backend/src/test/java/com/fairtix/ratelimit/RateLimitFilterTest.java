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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter}: which key a request is limited under.
 * Uses the real {@link RateLimitService} over a mocked Redisson client, so the
 * assertions are on the Redis key the limiter actually reads.
 *
 * <p>Exposure gate (a), RM:316. The browser client authenticates with the
 * {@code fairtix_token} cookie only, so a limiter that reads just the Bearer
 * header keys every signed-in user by IP.
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
