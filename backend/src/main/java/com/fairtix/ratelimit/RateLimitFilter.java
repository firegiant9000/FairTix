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

        String url = request.getRequestURI().replaceAll("/[0-9a-fA-F-]{36}", "/{id}");
        String key = resolveKey(request);

        try {
            if (!rateLimitService.isAllowed(key, url)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"status\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please try again later.\"}");
                return;
            }
        } catch (Exception e) {
            // Fail open — don't block users if Redis is down
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
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
        return "ip:" + resolveIp(request);
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

    private String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            // Take the first IP if multiple are present (client IP)
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
