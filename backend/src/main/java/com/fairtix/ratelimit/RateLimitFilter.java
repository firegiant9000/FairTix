package com.fairtix.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpStatus;
import io.jsonwebtoken.Claims;
import com.fairtix.auth.application.JwtService;

import java.io.IOException;

/**
 * Filter that intercepts incoming HTTP requests and applies rate limiting based
 * on the client's user ID (from JWT) or IP address and the requested URL.
 * Responds with HTTP 429 Too Many Requests if the limit is exceeded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String AUTH_COOKIE = "fairtix_token";

    private final JwtService jwtService;
    private final RateLimitService rateLimitService;

    public RateLimitFilter(RateLimitService rateLimitService, JwtService jwtService) {
        this.rateLimitService = rateLimitService;
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        if (isExempt(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String url = request.getRequestURI().replaceAll("/[0-9a-fA-F-]{36}", "/{id}");
        String key = resolveKey(request);

        boolean allowed;
        try {
            allowed = rateLimitService.isAllowed(key, url);
        } catch (Exception e) {
            if (rateLimitService.isAuthTier(url)) {
                // Fail closed: the auth-tier limit is the brute-force defence.
                log.warn("Rate limiter unavailable, rejecting auth request: {}", e.getMessage());
                reject(response, HttpStatus.SERVICE_UNAVAILABLE, "30",
                    "RATE_LIMIT_UNAVAILABLE", "Temporarily unavailable. Please try again shortly.");
                return;
            }
            // Fail open elsewhere, so a Redis outage doesn't take down browsing.
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            allowed = true;
        }

        if (!allowed) {
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "60",
                "RATE_LIMITED", "Too many requests. Please try again later.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // CORS preflights carry no credentials and would use up the caller's quota; health
    // must report a Redis outage rather than hang in (or be blocked by) the limiter.
    private boolean isExempt(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod())
            || request.getRequestURI().startsWith("/actuator/health");
    }

    private void reject(HttpServletResponse response, HttpStatus status, String retryAfterSeconds,
            String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setHeader("Retry-After", retryAfterSeconds);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"status\":" + status.value() + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private String resolveKey(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = jwtService.extractAllClaims(token);
                String userId = claims.get("userId", String.class);
                if (userId != null) {
                    return "user:" + userId;
                }
            } catch (Exception e) {
                // Invalid/expired token — fall through to IP-based limiting
            }
        }
        // getRemoteAddr() is already the client address: ForwardedHeadersConfig's
        // RemoteIpValve resolves X-Forwarded-For against the trusted proxies first.
        return "ip:" + request.getRemoteAddr();
    }

    // Same order as JwtAuthenticationFilter: the auth cookie (what the browser
    // client sends), then the Authorization header.
    private String resolveToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (AUTH_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
