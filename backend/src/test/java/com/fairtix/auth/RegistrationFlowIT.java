package com.fairtix.auth;

import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.support.PostgresIT;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Registration on PostgreSQL commits the new user before it writes the verification
 * token or sends the email, and never needs a second pooled connection.
 *
 * <p>Red on commit {@code 36d0c23}: the token was inserted in a {@code REQUIRES_NEW}
 * transaction while the user row was still uncommitted in the outer one, so the
 * {@code email_verification_tokens.user_id} foreign key rejected it. The failure was
 * swallowed, and no user ever got a verification email from sign-up. H2 hid this.
 */
class RegistrationFlowIT extends PostgresIT {

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @MockitoSpyBean private NotificationGate notificationGate;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private int register(String email) throws Exception {
    return mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"Password123!\"}".formatted(email)))
        .andReturn().getResponse().getStatus();
  }

  private int tokensFor(String email) {
    return jdbc.queryForObject("""
        SELECT count(*) FROM email_verification_tokens t
        JOIN users u ON u.id = t.user_id WHERE u.email = ?""", Integer.class, email);
  }

  @Test
  void register_createsVerificationToken() throws Exception {
    String email = "reg-" + UUID.randomUUID() + "@test.com";

    assertThat(register(email)).isEqualTo(200);

    assertThat(tokensFor(email)).isEqualTo(1);
  }

  @Test
  void register_holdsOneConnection() throws Exception {
    var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
    List<Integer> activeAtSend = new CopyOnWriteArrayList<>();
    List<Boolean> txAtSend = new CopyOnWriteArrayList<>();
    doAnswer(inv -> {
      activeAtSend.add(pool.getActiveConnections());
      txAtSend.add(TransactionSynchronizationManager.isActualTransactionActive());
      return inv.callRealMethod();
    }).when(notificationGate).sendEmail(any(), any(), anyString(), anyString(), anyString());

    assertThat(register("pool-" + UUID.randomUUID() + "@test.com")).isEqualTo(200);

    // Open-in-view keeps the request's first connection; the send must not add one.
    assertThat(activeAtSend).hasSize(1);
    assertThat(activeAtSend.get(0)).isLessThanOrEqualTo(1);
    assertThat(txAtSend).containsExactly(false);
  }

  @Test
  void concurrentSameEmail_one409() throws Exception {
    String email = "race-" + UUID.randomUUID() + "@test.com";
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(pool.submit(() -> {
          start.await();
          return register(email);
        }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> r : results) {
        statuses.add(r.get(60, TimeUnit.SECONDS));
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
      assertThat(tokensFor(email)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
