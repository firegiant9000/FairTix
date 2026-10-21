package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.support.PostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * X-Forwarded-For and X-Forwarded-Proto through a real Tomcat (exposure gate (a),
 * RM:316). MockMvc has no Tomcat valves, so this runs on a random port.
 *
 * <p>The test client connects from 127.0.0.1, which is a trusted (internal) proxy,
 * the way the load balancer is in a deployment. The client IP must then be the
 * rightmost untrusted X-Forwarded-For entry, never the leftmost one, which the
 * caller controls. The probe is the rate limiter itself: {@code /auth/me} is on
 * the auth tier (10 per minute), so only 429 versus anything else matters here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersIT extends PostgresIT {

  private static final int AUTH_LIMIT = 10;

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void leftmostXForwardedFor_cannotEscapeTheLimit() throws Exception {
    for (int i = 0; i < AUTH_LIMIT; i++) {
      assertThat(get("/auth/me", "10.9.8." + i + ", 203.0.113.50").statusCode())
          .as("call %d", i + 1).isNotEqualTo(429);
    }

    assertThat(get("/auth/me", "5.6.7.8, 203.0.113.50").statusCode())
        .as("a new leftmost entry from the same client").isEqualTo(429);
    assertThat(get("/auth/me", "5.6.7.8, 203.0.113.51").statusCode())
        .as("a different client behind the proxy").isNotEqualTo(429);
  }

  @Test
  void forwardedProtoHttps_getsHsts() throws Exception {
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
            .header("X-Forwarded-Proto", "https")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
  }

  private HttpResponse<String> get(String path, String xForwardedFor) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-Forwarded-For", xForwardedFor)
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
