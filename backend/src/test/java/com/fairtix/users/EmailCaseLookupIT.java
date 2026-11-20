package com.fairtix.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fairtix.auth.application.AuthService;
import com.fairtix.auth.application.LoginAttemptService;
import com.fairtix.auth.application.PasswordResetService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.application.TransferService;
import com.fairtix.users.application.UserEmailLookup;
import com.fairtix.users.domain.User;
import com.fairtix.users.dto.LoginRequest;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Accounts are found by email ignoring case, since registration keeps the address as typed
 * and {@code users.email} is unique only case-sensitively. Transfers resolve the recipient
 * like comps (ranked when several accounts match). Login and password reset ignore case only
 * when exactly one account matches; otherwise they use the exact match, so an ambiguous
 * address never reaches someone else's account.
 *
 * <p>Red before the change: a transfer to {@code RECIPIENT@...} and a login or reset as
 * {@code ALICE@...} found no account.
 */
class EmailCaseLookupIT extends PostgresIT {

  private static final String PASSWORD = "Str0ng!Passw0rd";
  private static final String OTHER_PASSWORD = "0ther!Passw0rd";

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private TransferService transferService;
  @Autowired private AuthService authService;
  @Autowired private PasswordResetService passwordResetService;
  @Autowired private LoginAttemptService loginAttemptService;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbcTemplate;

  // ---- transfers -----------------------------------------------------------

  @Test
  void transferToADifferentCaseEmail_reachesTheAccount() {
    User sender = newUser("sender@test.com", PASSWORD, true);
    User recipient = newUser("recipient@test.com", PASSWORD, true);
    UUID ticketId = buyOneTicket(sender);

    var request = transferService.createTransferRequest(ticketId, sender.getId(), "RECIPIENT@Test.com");

    assertThat(request.getToUser().getId()).isEqualTo(recipient.getId());
  }

  /** Ranked as comps rank it: a verified registered account before a comp stub. */
  @Test
  void transferToAnEmailMatchingTwoAccounts_reachesTheVerifiedRegisteredOne() {
    User sender = newUser("sender@test.com", PASSWORD, true);
    User stub = newUser("guest@test.com",
        UserEmailLookup.COMP_STUB_PASSWORD_PREFIX + "x", false);
    User registered = newUser("Guest@test.com", PASSWORD, true);
    UUID ticketId = buyOneTicket(sender);

    var request = transferService.createTransferRequest(ticketId, sender.getId(), "guest@test.com");

    assertThat(request.getToUser().getId()).isEqualTo(registered.getId()).isNotEqualTo(stub.getId());
  }

  // ---- login ---------------------------------------------------------------

  @Test
  void loginWithADifferentCaseEmail_withOneMatch_signsIn() {
    newUser("alice@test.com", PASSWORD, true);

    assertThat(authService.login(new LoginRequest("ALICE@test.com", PASSWORD, null))).isNotBlank();
  }

  /**
   * Two accounts differ only in case: the exact address signs in to its own account only,
   * and a third spelling matches neither.
   */
  @Test
  void loginWithAnEmailMatchingTwoAccounts_usesTheExactMatchOnly() {
    newUser("bob@test.com", PASSWORD, true);
    newUser("Bob@test.com", OTHER_PASSWORD, true);

    assertThat(authService.login(new LoginRequest("bob@test.com", PASSWORD, null))).isNotBlank();
    assertThatThrownBy(() -> authService.login(new LoginRequest("bob@test.com", OTHER_PASSWORD, null)))
        .as("never the other account's password")
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> authService.login(new LoginRequest("BOB@test.com", PASSWORD, null)))
        .as("an ambiguous spelling with no exact match signs in to nobody")
        .isInstanceOf(ResponseStatusException.class);
  }

  /**
   * The database folds the dotless i to I, so it matches alıce@ to alice@; Java does not,
   * and the lockout is keyed on Java's folding. Signing in that way would give each such
   * spelling its own lockout count, so it reaches no account.
   */
  @Test
  void loginWithASpellingOnlyTheDatabaseFolds_signsInToNobody() {
    newUser("alice@test.com", PASSWORD, true);
    assumeTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT upper('alıce@test.com') = upper('alice@test.com')", Boolean.class)),
        "this database's locale does not fold the dotless i");

    assertThatThrownBy(() -> authService.login(new LoginRequest("alıce@test.com", PASSWORD, null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** Every spelling that can reach an account counts toward that account's lockout. */
  @Test
  void failedLoginsInDifferentCases_shareOneLockoutCount() {
    newUser("erin@test.com", PASSWORD, true);

    for (String spelling : List.of("ERIN@test.com", "Erin@Test.com", "erin@test.com")) {
      assertThatThrownBy(() -> authService.login(new LoginRequest(spelling, OTHER_PASSWORD, null)))
          .isInstanceOf(ResponseStatusException.class);
    }

    assertThat(loginAttemptService.getAttemptCount("erin@TEST.com")).isEqualTo(3);
  }

  // ---- password reset ------------------------------------------------------

  @Test
  void resetWithADifferentCaseEmail_withOneMatch_issuesAToken() {
    User carol = newUser("carol@test.com", PASSWORD, true);

    passwordResetService.requestReset("CAROL@test.com");

    assertThat(resetTokenOwners()).containsExactly(carol.getId());
  }

  @Test
  void resetWithAnEmailMatchingTwoAccounts_usesTheExactMatchOnly() {
    User lower = newUser("dave@test.com", PASSWORD, true);
    newUser("Dave@test.com", OTHER_PASSWORD, true);

    passwordResetService.requestReset("DAVE@test.com");
    assertThat(resetTokenOwners()).as("an ambiguous spelling resets nobody").isEmpty();

    passwordResetService.requestReset("dave@test.com");
    assertThat(resetTokenOwners()).containsExactly(lower.getId());
  }

  // ---- V54 -----------------------------------------------------------------

  @Test
  void usersHaveANonUniqueUpperEmailIndex() {
    String def = jdbcTemplate.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE tablename = 'users'"
            + " AND indexname = 'idx_users_email_upper'", String.class);
    assertThat(def).contains("upper((email)::text)").doesNotContain("UNIQUE");
  }

  /**
   * The IgnoreCase lookups compare upper(email) = upper(?); with sequential scans off, the
   * planner can serve that comparison from the index (an empty table would otherwise always
   * be scanned).
   */
  @Test
  void theUpperEmailComparisonCanUseTheIndex() {
    List<String> plan = jdbcTemplate.execute((ConnectionCallback<List<String>>) con -> {
      try (var st = con.createStatement()) {
        st.execute("SET enable_seqscan = off");
        List<String> lines = new java.util.ArrayList<>();
        try (var rs = st.executeQuery(
            "EXPLAIN SELECT id FROM users WHERE upper(email) = upper('someone@test.com')")) {
          while (rs.next()) {
            lines.add(rs.getString(1));
          }
        } finally {
          // A pooled connection: never hand it back with sequential scans off.
          st.execute("RESET enable_seqscan");
        }
        return lines;
      }
    });
    assertThat(String.join("\n", plan)).contains("idx_users_email_upper");
  }

  // ---- helpers -------------------------------------------------------------

  private List<UUID> resetTokenOwners() {
    return jdbcTemplate.queryForList("SELECT user_id FROM password_reset_tokens", UUID.class);
  }

  private UUID buyOneTicket(User buyer) {
    Event event = eventRepository.save(
        new Event("Email Case Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    UUID seatId = seatRepository.save(
        new Seat(event, "A", "1", "1", new BigDecimal("50.00"))).getId();
    List<SeatHold> holds = seatHoldService.createHold(event.getId(), List.of(seatId), buyer.getId(), 10);
    seatHoldService.confirmHold(holds.get(0).getId(), buyer.getId());
    var order = orderService.createOrderWithPayment(
        buyer.getId(), List.of(holds.get(0).getId()), PaymentStatus.SUCCESS);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM tickets WHERE order_id = ?", UUID.class, order.getId());
  }

  private User newUser(String email, String password, boolean verified) {
    User user = new User();
    user.setEmail(email);
    user.setPassword(password.startsWith(UserEmailLookup.COMP_STUB_PASSWORD_PREFIX)
        ? password : passwordEncoder.encode(password));
    user.setEmailVerified(verified);
    return userRepository.save(user);
  }
}
