package com.fairtix.payments.api;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stops Tomcat from reading the rest of a webhook body that {@link StripeWebhookBodyLimitFilter}
 * refused.
 *
 * <p>After the response is sent, Tomcat normally reads and discards any unread request body (up
 * to its 2 MB {@code maxSwallowSize}) on the same worker thread, with no overall deadline, so a
 * sender trickling bytes would hold that thread long after the 408, 411 or 413. For a request
 * the filter marked, this valve switches that read off; Tomcat then closes the connection once
 * the response is written. A filter can't do this itself: the switch is on Tomcat's own request
 * object, which a valve receives directly.
 *
 * <p>It must be an engine valve. Switching the read off marks the request as failed; done inside
 * the host (as a context valve would), that makes the host's error handling forward the request
 * to {@code /error}, which then answers 401 instead of the 408/411/413. At engine level the host
 * has already finished, the same point where Tomcat does this itself for a 413 upload
 * ({@code Request.finishRequest}).
 */
@Configuration
public class StripeWebhookTomcatConfig {

  @Bean
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> stripeWebhookBodyDrainCustomizer() {
    return factory -> factory.addEngineValves(new SkipBodyDrainValve());
  }

  static final class SkipBodyDrainValve extends ValveBase {

    SkipBodyDrainValve() {
      super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
      try {
        getNext().invoke(request, response);
      } finally {
        if (request.getAttribute(StripeWebhookBodyLimitFilter.SKIP_BODY_DRAIN_ATTR) != null) {
          request.getCoyoteRequest().action(ActionCode.DISABLE_SWALLOW_INPUT, null);
        }
      }
    }
  }
}
