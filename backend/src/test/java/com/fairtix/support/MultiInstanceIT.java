package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.FairtixApplication;
import com.fairtix.auth.application.JwtService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.inventory.scheduler.HoldExpirationScheduler;
import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.scheduler.QueueAdmissionScheduler;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two application instances on one PostgreSQL and one Redis, as in a scaled-out deployment.
 * Instances A and B are full Spring Boot applications with their own web servers and small
 * connection pools, started on this suite's containers; this class's own context (from
 * {@link PostgresIT}) seeds the data and reads the results.
 *
 * <p>Proves the two things a second instance would otherwise break: a {@code @Scheduled} job
 * runs once per tick across both instances (ShedLock), and a browser streaming queue updates
 * from one instance hears about an admission made by the other (Redis fan-out).
 *
 * <p>Scheduling is off on both instances ({@code fairtix.scheduling.enabled=false}); the tests
 * call the job methods through the beans, which is the call the scheduler makes, so the
 * timing is deterministic. The ShedLock advice is on ({@code fairtix.scheduling.lock.enabled}).
 * Flyway is off on A and B: this class's context has already migrated the schema, and the
 * test Flyway strategy would truncate the seeded rows.
 */
class MultiInstanceIT extends PostgresIT {

  private static final int BATCH = 2;

  private static ConfigurableApplicationContext instanceA;
  private static ConfigurableApplicationContext instanceB;

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void startInstances() {
    if (instanceA == null) {
      instanceA = startInstance("a");
      instanceB = startInstance("b");
    }
    // ShedLock remembers which lock rows it has created and from then on only updates them.
    // PostgresIT truncates every table after each test, so put the row back, unlocked.
    jdbcTemplate.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by)"
        + " VALUES ('queueAdmission', timezone('utc', now()) - interval '1 hour',"
        + " timezone('utc', now()) - interval '1 hour', 'MultiInstanceIT') ON CONFLICT (name) DO NOTHING");
  }

  @AfterAll
  static void stopInstances() {
    for (ConfigurableApplicationContext instance : Arrays.asList(instanceA, instanceB)) {
      if (instance != null) {
        instance.close();
      }
    }
    instanceA = null;
    instanceB = null;
  }

  /**
   * Both instances' schedulers fire in the same interval. Without the lock each admits a
   * batch, so the event admits twice its configured rate; with it the second instance
   * skips the tick.
   */
  @Test
  void admissionJob_runsOncePerTickAcrossTwoInstances() {
    Event event = queueEvent(10);
    for (int i = 0; i < 5; i++) {
      queueService.joinQueue(event.getId(), newUser("wait" + i + "@multi.test").getId());
    }

    instanceA.getBean(QueueAdmissionScheduler.class).admitWaitingUsers();
    instanceB.getBean(QueueAdmissionScheduler.class).admitWaitingUsers();

    assertThat(countWithStatus(event, "ADMITTED")).isEqualTo(BATCH);
    assertThat(countWithStatus(event, "WAITING")).isEqualTo(5 - BATCH);
  }

  /**
   * The lock must be taken before the job's transaction begins: taken inside it, the lock
   * row stays invisible to the other instance until the job has finished.
   */
  @Test
  void lockAdvice_wrapsTheJobsTransaction() {
    Advisor[] advisors = ((Advised) instanceA.getBean(HoldExpirationScheduler.class)).getAdvisors();
    List<String> names = Stream.of(advisors).map(a -> a.getClass().getSimpleName()).toList();

    int lock = names.indexOf("MethodProxyScheduledLockAdvisor");
    int tx = names.indexOf("BeanFactoryTransactionAttributeSourceAdvisor");
    assertThat(lock).as("lock advisor in %s", names).isNotNegative();
    assertThat(tx).as("transaction advisor in %s", names).isNotNegative();
    assertThat(lock).as("lock advice outside the transaction, %s", names).isLessThan(tx);
  }

  /**
   * A browser is connected to instance B's SSE stream; instance A's admission tick admits
   * the user. B must push the new status, not leave the browser waiting for its next poll.
   */
  @Test
  @Timeout(60)
  void sseClientOnB_receivesAdmissionMadeOnA() throws Exception {
    Event event = queueEvent(10);
    User user = newUser("sse@multi.test");
    queueService.joinQueue(event.getId(), user.getId());

    String token = instanceB.getBean(JwtService.class)
        .generateToken(user.getId(), user.getEmail(), user.getRole().name());
    int portB = instanceB.getEnvironment().getRequiredProperty("local.server.port", Integer.class);

    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    // Not try-with-resources: close() waits for the open stream to end.
    HttpClient client = HttpClient.newHttpClient();
    try {
      HttpRequest request = HttpRequest.newBuilder(
              URI.create("http://localhost:" + portB + "/api/events/" + event.getId() + "/queue/stream"))
          .header("Authorization", "Bearer " + token)
          .header("Accept", "text/event-stream")
          .GET()
          .build();
      var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
      Thread.ofVirtual().start(() -> {
        try (Stream<String> body = response.join().body()) {
          body.forEach(lines::add);
        } catch (Exception ignored) {
          // stream closed at the end of the test
        }
      });

      assertThat(awaitLine(lines, "\"status\":\"WAITING\"")).as("initial status from B").isTrue();

      instanceA.getBean(QueueAdmissionScheduler.class).admitWaitingUsers();
      assertThat(countWithStatus(event, "ADMITTED")).isEqualTo(1);

      assertThat(awaitLine(lines, "\"status\":\"ADMITTED\"")).as("admission pushed by B").isTrue();
    } finally {
      client.shutdownNow();
    }
  }

  // ---- helpers ------------------------------------------------------------

  /** Overrides go in as command-line arguments: builder defaults lose to application.properties. */
  private static ConfigurableApplicationContext startInstance(String name) {
    return new SpringApplicationBuilder(FairtixApplication.class)
        .web(WebApplicationType.SERVLET)
        .profiles("it")
        .run(
            "--server.port=0",
            "--spring.application.name=fairtix-" + name,
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.datasource.hikari.maximum-pool-size=3",
            "--spring.datasource.hikari.minimum-idle=1",
            "--spring.flyway.enabled=false",
            "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
            "--fairtix.scheduling.enabled=false",
            "--fairtix.scheduling.lock.enabled=true",
            "--queue.max-admission-batch=" + BATCH);
  }

  private static boolean awaitLine(BlockingQueue<String> lines, String fragment) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      String line = lines.poll(200, TimeUnit.MILLISECONDS);
      if (line != null && line.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  private Event queueEvent(int seats) {
    Event event = new Event("Two-Instance Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, null);
    event = eventRepository.save(event);
    for (int i = 0; i < seats; i++) {
      seatRepository.save(new Seat(event, "A", "1", String.valueOf(i + 1), new BigDecimal("40.00")));
    }
    return event;
  }

  private int countWithStatus(Event event, String status) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM queue_entries WHERE event_id = ? AND status = ?",
        Integer.class, event.getId(), status);
    return count == null ? 0 : count;
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
