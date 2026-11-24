package com.fairtix.payments.api;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Surfaces server-side failures (5xx or a thrown exception) of the Stripe webhook endpoints.
 * Stripe retries on 5xx, so a persistent failure is otherwise silent. Records the Micrometer
 * counter {@code stripe.webhook.failures} (tag {@code endpoint=platform|connect}) and logs one
 * ERROR line starting with {@value #MARKER} as a log-alerting hook. Never logs the payload,
 * signature header or exception message. 4xx (e.g. bad signature) is not a failure here.
 *
 * <p>Registered as a servlet-container {@code @Component} filter; it only observes the final
 * status, so its position relative to other filters does not affect correctness.
 */
@Component
public class StripeWebhookFailureFilter extends OncePerRequestFilter {

  static final String MARKER = "STRIPE_WEBHOOK_FAILURE";
  static final String METRIC = "stripe.webhook.failures";
  private static final String PLATFORM_PATH = "/api/webhooks/stripe";
  private static final String CONNECT_PATH = "/api/webhooks/stripe-connect";

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookFailureFilter.class);

  private final MeterRegistry registry;

  public StripeWebhookFailureFilter(MeterRegistry registry) {
    this.registry = registry;
    // Register both series up front so they exist (at 0) before the first failure.
    registry.counter(METRIC, "endpoint", "platform");
    registry.counter(METRIC, "endpoint", "connect");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return endpointOf(request) == null;
  }

  private static String endpointOf(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path == null) {
      return null;
    }
    if (path.equals(PLATFORM_PATH)) {
      return "platform";
    }
    if (path.equals(CONNECT_PATH)) {
      return "connect";
    }
    return null;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    String endpoint = endpointOf(request);
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException | Error e) {
      record(endpoint, 500, e);
      throw e;
    }
    if (response.getStatus() >= 500) {
      record(endpoint, response.getStatus(), null);
    }
  }

  private void record(String endpoint, int status, Throwable thrown) {
    registry.counter(METRIC, "endpoint", endpoint).increment();
    if (thrown == null) {
      log.error("{} endpoint={} status={}", MARKER, endpoint, status);
    } else {
      log.error("{} endpoint={} status={} exception={}", MARKER, endpoint, status,
          thrown.getClass().getName());
    }
  }
}
