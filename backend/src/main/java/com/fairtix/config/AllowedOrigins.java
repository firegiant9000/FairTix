package com.fairtix.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The browser origins allowed to call the API with credentials, from
 * {@code app.cors.allowed-origins}. One parsed list serves the CORS configuration,
 * the CSRF origin check and the rate limiter's own responses, so they can't drift.
 */
@Component
public class AllowedOrigins {

  private final List<String> origins;

  public AllowedOrigins(
      @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}") String raw) {
    // Blank entries are dropped: an empty variable must allow nothing, not a "" origin.
    this.origins = Arrays.stream(raw.split(","))
        .map(String::trim)
        .map(AllowedOrigins::stripTrailingSlash)
        .filter(origin -> !origin.isEmpty())
        .toList();
  }

  public List<String> list() {
    return origins;
  }

  /** Exact match, ignoring case, as Spring's CORS origin check does. */
  public boolean contains(String origin) {
    if (origin == null || origin.isBlank()) {
      return false;
    }
    return origins.stream().anyMatch(origin::equalsIgnoreCase);
  }

  private static String stripTrailingSlash(String origin) {
    return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
  }
}
