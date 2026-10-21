package com.fairtix.auth.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrent failed logins can't starve the connection pool. Each one writes a
 * {@code LOGIN_FAILED} audit row (REQUIRES_NEW), and anyone can trigger it
 * without signing in, so it must not need a second connection while holding one.
 *
 * <p>Red on commit {@code RED_COMMIT}: {@code AuthController.login} was
 * {@code @Transactional}, so each failed login held its connection while the
 * audit write waited for another. With more concurrent failures than the pool
 * (4 in tests), every request stalled until the Hikari timeout and returned 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.datasource.hikari.connection-timeout=3000")
class FailedLoginPoolTest {

  private static final int CONCURRENT = 12; // 3x the test pool of 4

  @Autowired private WebApplicationContext context;

  @Test
  void concurrentFailedLogins_allReturn401() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < CONCURRENT; i++) {
        String email = "nobody-" + UUID.randomUUID() + "@test.com";
        results.add(pool.submit(() -> {
          start.await();
          return mockMvc.perform(post("/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"email\": \"%s\", \"password\": \"Wrong-P4ss!\" }".formatted(email)))
              .andReturn().getResponse().getStatus();
        }));
      }
      start.countDown();

      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get(60, TimeUnit.SECONDS));
      }
      assertThat(statuses).containsOnly(401);
    } finally {
      pool.shutdownNow();
    }
  }
}
