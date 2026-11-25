package com.fairtix.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
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
 * Lets a filter that refuses a request stop Tomcat from reading the rest of its body.
 *
 * <p>After the response is sent, Tomcat normally reads and discards any unread request body (up
 * to its 2 MB {@code maxSwallowSize}) on the same worker thread, with no overall deadline, so a
 * sender trickling bytes would hold that thread long after the rejection. A request passed to
 * {@link #mark} has that read switched off; Tomcat then closes the connection once the response
 * is written. A filter can't do this itself: the switch is on Tomcat's own request object, which
 * a valve receives directly.
 *
 * <p>Only mark a request whose response status is 400 or higher. Switching the read off marks
 * the request as failed, and Tomcat rewrites a lower status that isn't committed yet to 500.
 *
 * <p>If the client has already sent body bytes, closing without reading them can reach the
 * client as a connection reset instead of the response. Above 2 MB Tomcat already behaved this
 * way.
 *
 * <p>It must be an engine valve. Done inside the host (as a context valve would), marking the
 * request as failed makes the host's error handling forward it to {@code /error}, which then
 * answers 401 instead of the original status. At engine level the host has already finished,
 * the same point where Tomcat does this itself for a 413 upload ({@code Request.finishRequest}).
 */
@Configuration
public class SkipBodyDrainConfig {

  static final String ATTR = SkipBodyDrainConfig.class.getName() + ".skip";

  /** Tomcat won't read the rest of this request's body. Only for a 4xx/5xx response. */
  public static void mark(HttpServletRequest request) {
    request.setAttribute(ATTR, Boolean.TRUE);
  }

  public static boolean isMarked(HttpServletRequest request) {
    return request.getAttribute(ATTR) != null;
  }

  @Bean
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> skipBodyDrainCustomizer() {
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
        if (request.getAttribute(ATTR) != null) {
          request.getCoyoteRequest().action(ActionCode.DISABLE_SWALLOW_INPUT, null);
        }
      }
    }
  }
}
