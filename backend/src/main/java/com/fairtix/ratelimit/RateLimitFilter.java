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
import org.springframework.web.util.UrlPathHelper;
import org.springframework.http.HttpStatus;
import io.jsonwebtoken.Claims;
import com.fairtix.auth.application.JwtService;
import com.fairtix.config.AllowedOrigins;

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
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final JwtService jwtService;
    private final RateLimitService rateLimitService;
    private final AllowedOrigins allowedOrigins;

    public RateLimitFilter(RateLimitService rateLimitService, JwtService jwtService,
            AllowedOrigins allowedOrigins) {
        this.rateLimitService = rateLimitService;
        this.jwtService = jwtService;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        // The decoded path, as Spring MVC routes it: the raw URI would give every
        // percent-encoded spelling of /auth/login its own bucket.
        String path = PATH_HELPER.getPathWithinApplication(request);
        if (isExempt(request, path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String url = path.replaceAll("/[0-9a-fA-F-]{36}", "/{id}");
        boolean credentialEndpoint = rateLimitService.isCredentialEndpoint(url);
        // Credential endpoints are always keyed by IP: registration hands out a cookie
        // at once, so an account key there would let one client mint fresh buckets.
        String key = credentialEndpoint ? "ip:" + request.getRemoteAddr() : resolveKey(request);

        boolean allowed;
        try {
            allowed = rateLimitService.isAllowed(key, url);
        } catch (Exception e) {
            // The class name only: Redisson's messages carry the command, key and client IP.
            if (credentialEndpoint) {
                // Fail closed: this limit is the brute-force defence.
                log.warn("Rate limiter unavailable, rejecting credential request: {}", e.getClass().getSimpleName());
                reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "30",
                    "RATE_LIMIT_UNAVAILABLE", "Temporarily unavailable. Please try again shortly.");
                return;
            }
            // Fail open elsewhere, so a Redis outage doesn't take down browsing or sign out
            // every session (/auth/me).
            log.warn("Rate limiter unavailable, allowing request: {}", e.getClass().getSimpleName());
            allowed = true;
        }

        if (!allowed) {
            reject(request, response, HttpStatus.TOO_MANY_REQUESTS, "60",
                "RATE_LIMITED", "Too many requests. Please try again later.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // CORS preflights carry no credentials and would use up the caller's quota; health
    // must report a Redis outage rather than hang in (or be blocked by) the limiter.
    private boolean isExempt(HttpServletRequest request, String path) {
        return "OPTIONS".equals(request.getMethod())
            || path.equals("/actuator/health")
            || path.startsWith("/actuator/health/");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String retryAfterSeconds, String code, String message) throws IOException {
        // This filter runs before Spring Security's CorsFilter, so the rejection carries
        // its own CORS headers; without them the SPA sees a network error, not the 429.
        String origin = request.getHeader("Origin");
        response.addHeader("Vary", "Origin");
        if (allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
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
