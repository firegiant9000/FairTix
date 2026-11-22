package com.fairtix.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.organizations.application.OrgScopeInterceptor;
import com.fairtix.queue.application.QueueService;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * An open waiting-room stream ({@code GET /queue/stream}) must not keep a pooled
 * database connection.
 *
 * <p>The stream is an async request that stays open for up to three minutes. Under
 * open-in-view the request's EntityManager lives until the async request completes,
 * and once the initial status read has acquired a connection, Hibernate holds it for
 * the EntityManager's life. Ten open waiting rooms then took the whole pool, and
 * {@code /queue/status} and new streams timed out after 30 s (seen locally on
 * 2026-11-22: {@code total=10, active=10, idle=0, waiting=7}).
 */
class QueueStreamPoolIT extends PostgresIT {

  private static final int STREAMS = 3;

  @Autowired private WebApplicationContext context;
  @Autowired private DataSource dataSource;
  @Autowired private EventRepository eventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private QueueService queueService;
  @Autowired @Qualifier("requestMappingHandlerMapping") private HandlerMapping handlerMapping;

  private MockMvc mockMvc;
  private HikariPoolMXBean pool;
  private final List<MvcResult> openStreams = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
  }

  /** Completes every stream still open, so no test leaves connections checked out. */
  @AfterEach
  void closeStreams() {
    for (MvcResult stream : openStreams) {
      if (stream.getRequest().isAsyncStarted()) {
        stream.getRequest().getAsyncContext().complete();
      }
    }
  }

  @Test
  void openStreams_holdNoPooledConnection() throws Exception {
    Event event = queueEvent();
    List<User> users = new ArrayList<>();
    for (int i = 0; i < STREAMS; i++) {
      User user = newUser("stream" + i + "@test.com");
      queueService.joinQueue(event.getId(), user.getId());
      users.add(user);
    }
    int activeBefore = pool.getActiveConnections();

    for (User user : users) {
      openStreams.add(openStream(event, user));
    }

    assertThat(openStreams).allSatisfy(stream -> {
      assertThat(stream.getRequest().isAsyncStarted()).as("stream still open").isTrue();
      assertThat(stream.getResponse().getContentAsString()).as("initial status sent")
          .contains("\"status\":\"WAITING\"");
    });
    assertThat(pool.getActiveConnections())
        .as("pooled connections checked out while %d streams are open", STREAMS)
        .isEqualTo(activeBefore);
  }

  /**
   * Open-in-view still covers the other endpoints, and runs after
   * {@code OrgScopeInterceptor} as Boot's registration did: ahead of it, an org
   * denial would hold the request's connection while its {@code REQUIRES_NEW} audit
   * write waits for a second one.
   */
  @Test
  void openInView_coversOtherEndpoints_afterOrgScope_andSkipsOnlyTheStream() throws Exception {
    List<Object> status = interceptorsFor("/api/events/" + UUID.randomUUID() + "/queue/status");
    List<Object> stream = interceptorsFor("/api/events/" + UUID.randomUUID() + "/queue/stream");

    int osiv = indexOfOpenInView(status);
    int orgScope = indexOf(status, OrgScopeInterceptor.class);
    assertThat(osiv).as("open-in-view on /queue/status").isNotNegative();
    assertThat(orgScope).as("org scope on /queue/status").isNotNegative();
    assertThat(osiv).as("open-in-view runs after org scope").isGreaterThan(orgScope);
    assertThat(indexOfOpenInView(stream)).as("open-in-view on /queue/stream").isNegative();
  }

  private List<Object> interceptorsFor(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    ServletRequestPathUtils.parseAndCache(request);
    HandlerExecutionChain chain = handlerMapping.getHandler(request);
    assertThat(chain).as("handler for %s", path).isNotNull();
    return new ArrayList<>(chain.getInterceptorList());
  }

  private static int indexOfOpenInView(List<Object> interceptors) {
    for (int i = 0; i < interceptors.size(); i++) {
      if (interceptors.get(i) instanceof WebRequestHandlerInterceptorAdapter adapter
          && ReflectionTestUtils.getField(adapter, "requestInterceptor")
              instanceof OpenEntityManagerInViewInterceptor) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOf(List<Object> interceptors, Class<?> type) {
    for (int i = 0; i < interceptors.size(); i++) {
      if (type.isInstance(interceptors.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private MvcResult openStream(Event event, User user) throws Exception {
    return mockMvc.perform(get("/api/events/{eventId}/queue/stream", event.getId())
            .accept(MediaType.TEXT_EVENT_STREAM)
            .with(WithMockPrincipal.user(user.getId(), user.getEmail())))
        .andReturn();
  }

  private Event queueEvent() {
    Event event = new Event("Queued Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, null);
    return eventRepository.save(event);
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
