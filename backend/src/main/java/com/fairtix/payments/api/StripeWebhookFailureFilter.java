package com.fairtix.payments.api;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Surfaces failed deliveries of the Stripe webhook endpoints. Stripe retries any non-2xx
 * answer, so a persistent failure is otherwise silent. A POST that ends with a non-2xx status
 * other than 400 (400 is a bad or missing signature, which is not our failure), or that throws,
 * increments the Micrometer counter {@code stripe.webhook.failures} (tags
 * {@code endpoint=platform|connect}, {@code status=<code>}) and logs one ERROR line starting
 * with {@value #MARKER} as a log-alerting hook. Never logs the payload, signature header or
 * exception message. Non-POST requests (scanners) are ignored.
 *
 * <p>Runs at highest precedence so it wraps the rate limiter and security: a 429 or 403 on a
 * webhook is counted too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StripeWebhookFailureFilter extends OncePerRequestFilter {

  static final String MARKER = "STRIPE_WEBHOOK_FAILURE";
  static final String METRIC = "stripe.webhook.failures";
  private static final String PLATFORM_PATH = "/api/webhooks/stripe";
  private static final String CONNECT_PATH = "/api/webhooks/stripe-connect";
  private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookFailureFilter.class);

  private final MeterRegistry registry;

  public StripeWebhookFailureFilter(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equalsIgnoreCase(request.getMethod()) || endpointOf(request) == null;
  }

  private static String endpointOf(HttpServletRequest request) {
    String path = PATH_HELPER.getPathWithinApplication(request);
    if (PLATFORM_PATH.equals(path)) {
      return "platform";
    }
    if (CONNECT_PATH.equals(path)) {
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
    int status = response.getStatus();
    if ((status < 200 || status >= 300) && status != 400) {
      record(endpoint, status, null);
    }
  }

  private void record(String endpoint, int status, Throwable thrown) {
    registry.counter(METRIC, "endpoint", endpoint, "status", String.valueOf(status)).increment();
    if (thrown == null) {
      log.error("{} endpoint={} status={}", MARKER, endpoint, status);
    } else {
      log.error("{} endpoint={} status={} exception={}", MARKER, endpoint, status,
          thrown.getClass().getName());
    }
  }
}
