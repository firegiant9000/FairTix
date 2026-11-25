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
 * answer, so a persistent failure is otherwise silent.
 *
 * <p>Only requests whose Stripe signature verified are counted: the controllers set the request
 * attribute {@value #VERIFIED_ENDPOINT_ATTR} (to {@code platform} or {@code connect}) right
 * after {@code Webhook.constructEvent} succeeds. If such a request ends non-2xx (any status,
 * including 400 from a handler) or throws, this filter increments the Micrometer counter
 * {@code stripe.webhook.failures} (tags {@code endpoint}, {@code status=<code>}) and logs one
 * ERROR line starting with {@value #MARKER} as a log-alerting hook. It never logs the payload,
 * signature header or exception message.
 *
 * <p>Nobody without the signing secret can fire the alert. Trade-off: failures before
 * verification (database down at transaction begin, a signed but unparseable body, 415, 403,
 * 429, bad-signature 400) are not counted. Runs at highest precedence so it sees exceptions
 * and statuses from every filter below it. Only POSTs to the two webhook paths are inspected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StripeWebhookFailureFilter extends OncePerRequestFilter {

  /** Request attribute set by the webhook controllers once the signature verified. */
  public static final String VERIFIED_ENDPOINT_ATTR =
      StripeWebhookFailureFilter.class.getName() + ".verifiedEndpoint";

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
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = PATH_HELPER.getPathWithinApplication(request);
    return !PLATFORM_PATH.equals(path) && !CONNECT_PATH.equals(path);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException | Error e) {
      String endpoint = verifiedEndpoint(request);
      if (endpoint != null) {
        record(endpoint, 500, e);
      }
      throw e;
    }
    int status = response.getStatus();
    if (status < 200 || status >= 300) {
      String endpoint = verifiedEndpoint(request);
      if (endpoint != null) {
        record(endpoint, status, null);
      }
    }
  }

  private static String verifiedEndpoint(HttpServletRequest request) {
    return request.getAttribute(VERIFIED_ENDPOINT_ATTR) instanceof String s ? s : null;
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
