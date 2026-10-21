package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Parsing {@code app.cors.allowed-origins} (exposure gate (a), RM:327).
 *
 * <p>Intended-red commit: {@code 3f2146f}.
 */
class AllowedOriginsTest {

  /** An empty variable used to yield a {@code ""} origin in the CORS allow-list. */
  @Test
  void blankEntries_areDropped() {
    AllowedOrigins origins = new AllowedOrigins(" , https://app.example ,,");

    assertThat(origins.list()).containsExactly("https://app.example");
  }

  @Test
  void emptyValue_allowsNoOrigin() {
    AllowedOrigins origins = new AllowedOrigins("");

    assertThat(origins.list()).isEmpty();
    assertThat(origins.contains("")).isFalse();
  }

  /** Browsers never send a trailing slash; Spring's CORS check strips one from config too. */
  @Test
  void trailingSlash_isIgnored() {
    AllowedOrigins origins = new AllowedOrigins("https://app.example/");

    assertThat(origins.contains("https://app.example")).isTrue();
  }

  @Test
  void contains_isExactExceptForCase() {
    AllowedOrigins origins = new AllowedOrigins("https://app.example");

    assertThat(origins.contains("https://APP.example")).isTrue();
    assertThat(origins.contains("https://app.example.evil.test")).isFalse();
    assertThat(origins.contains("http://app.example")).isFalse();
    assertThat(origins.contains("null")).isFalse();
    assertThat(origins.contains(null)).isFalse();
  }
}
