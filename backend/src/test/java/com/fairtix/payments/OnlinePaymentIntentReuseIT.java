package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.payments.application.StripeIdempotencyKeys;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.StripeIntent;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The online PaymentIntent is stored per user and hold set, so a second request
 * (or a request after Stripe's idempotency window) gets the same intent back.
 *
 * <p>Stripe is never called: the service is a spy with stubbed calls.
 */
@TestPropertySource(properties = "stripe.enabled=true")
class OnlinePaymentIntentReuseIT extends PostgresIT {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private StripePaymentService stripePaymentService;
  @MockitoSpyBean private SeatHoldService seatHoldService;

  private User buyer;
  private SeatHold hold;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setEmail("intentreuse@test.com");
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    buyer = userRepository.save(user);
    Event event = eventRepository.save(
        new Event("Intent Reuse Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("40.00")));
    hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
  }

  /** Both requests pass the lookup before either inserts; one row remains, one secret is returned. */
  @Test
  @Timeout(30)
  void concurrentIntentCalls_writeOneRow_andReturnTheSameSecret() throws Exception {
    // Two simultaneous hold extensions bump the same seat version, so one request would
    // lose with CONCURRENT_MODIFICATION before reaching the intent service. That race is
    // not what this test is about, so the extension is skipped here.
    doNothing().when(seatHoldService).extendConfirmedHoldsForPayment(any(), any());
    CyclicBarrier barrier = new CyclicBarrier(2);
    doAnswer(inv -> {
      barrier.await(10, TimeUnit.SECONDS);
      return new StripeIntent("pi_race", "requires_payment_method", "sec_race");
    }).when(stripePaymentService)
        .createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<MvcResult> call = () -> requestIntent().andReturn();
      Future<MvcResult> first = pool.submit(call);
      Future<MvcResult> second = pool.submit(call);
      for (MvcResult result : List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))) {
        assertThat(result.getResponse().getStatus())
            .as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("sec_race");
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(count("SELECT COUNT(*) FROM online_payment_intents WHERE status = 'ACTIVE'")).isEqualTo(1);
  }

  /** The stored intent is older than Stripe's key window; it is retrieved, not created again. */
  @Test
  void storedIntentPastKeyWindow_isReturned_notRecreated() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO online_payment_intents (id, user_id, hold_set_hash, amount_cents, routing_hash,"
            + " stripe_payment_intent_id, status, created_at)"
            + " VALUES (gen_random_uuid(), ?, ?, 4000, ?, 'pi_stored', 'ACTIVE', NOW() - INTERVAL '2 days')",
        buyer.getId(),
        StripeIdempotencyKeys.holdSetHash(List.of(hold.getId())),
        StripeIdempotencyKeys.routingHash(null));
    doReturn(new StripeIntent("pi_stored", "requires_payment_method", "sec_stored"))
        .when(stripePaymentService).retrievePaymentIntent("pi_stored");

    requestIntent()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecret").value("sec_stored"));

    verify(stripePaymentService, never())
        .createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
  }

  private org.springframework.test.web.servlet.ResultActions requestIntent() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc.perform(post("/api/payments/intent")
        .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            { "holdIds": ["%s"] }
            """.formatted(hold.getId())));
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
