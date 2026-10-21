package com.fairtix.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The browser origins allowed to call the API with credentials, from
 * {@code app.cors.allowed-origins}.
 */
@Component
public class AllowedOrigins {

  private final List<String> origins;

  public AllowedOrigins(
      @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}") String raw) {
    this.origins = Arrays.stream(raw.split(","))
        .map(String::trim)
        .collect(Collectors.toList());
  }

  public List<String> list() {
    return origins;
  }

  public boolean contains(String origin) {
    return false;
  }
}
