package com.fairtix.payments.api;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = "stripe.enabled=true")
@Transactional
class StripePaymentControllerTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldRepository seatHoldRepository;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockitoSpyBean
  private StripePaymentService stripePaymentService;

  private MockMvc mockMvc;
  private User user;
  private SeatHold confirmedHold;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();

    user = new User();
    user.setEmail("stripetest@test.com");
    user.setPassword(passwordEncoder.encode("Test1234!"));
    user.setEmailVerified(true);
    user = userRepository.save(user);

    Event event = new Event("Stripe Test Event", null, Instant.now().plusSeconds(86400 * 30), null);
    event = eventRepository.save(event);

    Seat seat = new Seat(event, "B", "2", "3", new BigDecimal("50.00"));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);

    confirmedHold = new SeatHold(seat, user.getId(), Instant.now().plusSeconds(600));
    confirmedHold.setStatus(HoldStatus.CONFIRMED);
    confirmedHold = seatHoldRepository.save(confirmedHold);
  }

  @Test
  void createIntent_returnsClientSecret() throws Exception {
    // The M2-07 Connect refactor added a third ConnectContext parameter on
    // the PaymentIntent factory. PaymentController now calls the 3-arg
    // overload directly with a (possibly null) ConnectContext, so the mock
    // must cover that signature; the 2-arg overload still exists but is no
    // longer on the controller's call path.
    doReturn("pi_test_secret_xyz").when(stripePaymentService)
        .createPaymentIntent(anyLong(), anyString(), any(ConnectContext.class));
    doReturn("pi_test_secret_xyz").when(stripePaymentService)
        .createPaymentIntent(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull());

    String body = """
        { "holdIds": ["%s"] }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/intent")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecret").value("pi_test_secret_xyz"));
  }

  @Test
  void checkout_withStripeIntentId_succeeds() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_test_123"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));
  }

  @Test
  void checkout_withStripeIntentId_issuesTicket() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_ticket_verify"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("VALID"))
        .andExpect(jsonPath("$[0].price").value(50.00));
  }

  @Test
  void checkout_withStripeIntentId_failsWhenNotSucceeded() throws Exception {
    doReturn(false).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_test_456"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isPaymentRequired());
  }
}
