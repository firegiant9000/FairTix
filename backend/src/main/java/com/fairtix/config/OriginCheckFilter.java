package com.fairtix.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence for the cookie session (RM:317, decision D3): Spring's token CSRF stays
 * off, because the SPA is cross-site and can't read the API's cookies. Instead, an
 * unsafe request that carries an auth cookie must name its source in {@code Origin}
 * (or, if that is missing, {@code Referer}), and the source must be an allowed origin
 * or the API's own. With neither header, it is rejected.
 *
 * <p>Requests without an auth cookie are not checked: a Bearer header or a Stripe
 * signature is never attached by the browser on its own, so they can't be forged
 * cross-site.
 *
 * <p>Registered in the security chain, after CORS, by {@link SecurityConfig}; it is
 * deliberately not a {@code @Component}, which would also register it as a servlet
 * filter. Rejections are not audited: each would be a database write an attacker can
 * trigger at will.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(OriginCheckFilter.class);
  private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final Set<String> AUTH_COOKIES = Set.of("fairtix_token", "fairtix_refresh");

  private final AllowedOrigins allowedOrigins;

  public OriginCheckFilter(AllowedOrigins allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (UNSAFE_METHODS.contains(request.getMethod()) && hasAuthCookie(request)
        && !isTrustedSource(request)) {
      log.debug("Rejected cookie-authenticated {} {}: untrusted or missing Origin/Referer",
          request.getMethod(), request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"status\":403,\"code\":\"CSRF_ORIGIN_REJECTED\",\"message\":\"Request origin not allowed\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean hasAuthCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    for (Cookie cookie : cookies) {
      if (AUTH_COOKIES.contains(cookie.getName())) {
        return true;
      }
    }
    return false;
  }

  private boolean isTrustedSource(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    String source = origin != null ? normalize(origin) : normalize(request.getHeader("Referer"));
    if (source == null) {
      return false;
    }
    return allowedOrigins.contains(source) || source.equalsIgnoreCase(ownOrigin(request));
  }

  /**
   * The {@code scheme://host[:port]} of a URL, with a default port dropped; null if
   * the value is missing, opaque (such as the literal {@code null} origin) or malformed.
   */
  static String normalize(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    // Only scheme://authority matters. Browsers leave characters such as | { } ^
    // unescaped in a Referer's path and query, which java.net.URI would reject.
    String value = url.trim();
    int authorityStart = value.indexOf("://");
    if (authorityStart > 0) {
      int end = value.length();
      for (char c : new char[] {'/', '?', '#'}) {
        int i = value.indexOf(c, authorityStart + 3);
        if (i >= 0 && i < end) {
          end = i;
        }
      }
      value = value.substring(0, end);
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null) {
        return null;
      }
      scheme = scheme.toLowerCase(Locale.ROOT);
      return scheme + "://" + host + portSuffix(scheme, uri.getPort());
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The API's own origin, as this request reached it. Behind the load balancer, the
   * RemoteIpValve has already applied X-Forwarded-Proto. A cross-site page can't set
   * the Host header the browser sends, so this can't be forged.
   */
  private static String ownOrigin(HttpServletRequest request) {
    String scheme = request.getScheme().toLowerCase(Locale.ROOT);
    return scheme + "://" + request.getServerName() + portSuffix(scheme, request.getServerPort());
  }

  private static String portSuffix(String scheme, int port) {
    boolean defaultPort = port == -1
        || ("http".equals(scheme) && port == 80)
        || ("https".equals(scheme) && port == 443);
    return defaultPort ? "" : ":" + port;
  }
}
