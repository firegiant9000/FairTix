package com.fairtix.payments.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Caps the body of the two Stripe webhook endpoints. Their controllers bind the whole body as
 * a String before checking the signature, and the routes are public, so without a cap anyone
 * could make the server buffer an arbitrarily large body.
 *
 * <p>A POST whose Content-Length exceeds the cap is answered 413 without reading the body.
 * Otherwise at most cap + 1 bytes are read (this also covers chunked bodies and an understated
 * Content-Length); more than the cap is answered 413, and anything else reaches the controller
 * as an in-memory copy of the exact bytes, which the signature covers.
 *
 * <p>Runs after {@code RateLimitFilter}, so a throttled caller is turned away before any body
 * is read. A legitimate Stripe event over the cap would be retried by Stripe and keep failing;
 * the WARN line (path, cap, declared length and whether a signature header was sent; no client
 * IP, no header value) is the trace of that.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class StripeWebhookBodyLimitFilter extends OncePerRequestFilter {

  private static final String PLATFORM_PATH = "/api/webhooks/stripe";
  private static final String CONNECT_PATH = "/api/webhooks/stripe-connect";
  private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookBodyLimitFilter.class);

  private final int maxBodyBytes;

  public StripeWebhookBodyLimitFilter(
      @Value("${stripe.webhook-max-body-bytes:1048576}") int maxBodyBytes) {
    if (maxBodyBytes <= 0 || maxBodyBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "stripe.webhook-max-body-bytes must be between 1 and " + (Integer.MAX_VALUE - 1));
    }
    this.maxBodyBytes = maxBodyBytes;
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
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (request.getContentLengthLong() > maxBodyBytes) {
      reject(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
    if (body.length > maxBodyBytes) {
      reject(request, response);
      return;
    }
    filterChain.doFilter(new CachedBodyRequest(request, body), response);
  }

  // signed=true means only that a Stripe-Signature header was sent (its value is never logged).
  // It is unverified, since the body can't be checked without reading it, and any client can
  // send one: a hint for triage, not evidence that Stripe sent it.
  private void reject(HttpServletRequest request, HttpServletResponse response) {
    log.warn("Stripe webhook body over {} bytes rejected path={} declaredLength={} signed={}",
        maxBodyBytes, PATH_HELPER.getPathWithinApplication(request),
        request.getContentLengthLong(), request.getHeader("Stripe-Signature") != null);
    response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
  }

  /** Serves the already-read body to the controller. */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("synchronous body only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
