package com.fairtix.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserEmailLookupTest {

  private UserRepository users;
  private UserEmailLookup lookup;

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    lookup = new UserEmailLookup(users);
  }

  // ---- findForSignIn -------------------------------------------------------

  @Test
  void signIn_oneMatchInAnotherCase_isFound() {
    User alice = user("alice@x.com", "$2a$hash", true);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("ALICE@x.com")).thenReturn(List.of(alice));

    assertThat(lookup.findForSignIn("ALICE@x.com")).contains(alice);
  }

  @Test
  void signIn_twoMatches_usesTheExactOneOnly() {
    User lower = user("bob@x.com", "$2a$hash", true);
    User upper = user("Bob@x.com", "$2a$hash", true);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc(anyString())).thenReturn(List.of(lower, upper));

    assertThat(lookup.findForSignIn("Bob@x.com")).contains(upper);
    assertThat(lookup.findForSignIn("BOB@x.com")).isEmpty();
  }

  /**
   * PostgreSQL's upper() folds the dotless i to I, so the database matches alıce@ to alice@;
   * Java's lower-casing keeps them apart, and the login lockout is keyed on that. Such a
   * match is dropped, or each spelling would get its own lockout count.
   */
  @Test
  void signIn_aMatchOnlyTheDatabaseFoldsTogether_isDropped() {
    User alice = user("alice@x.com", "$2a$hash", true);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("alıce@x.com")).thenReturn(List.of(alice));

    assertThat(lookup.findForSignIn("alıce@x.com")).isEmpty();
  }

  @Test
  void signIn_nullOrEmpty_isEmpty() {
    assertThat(lookup.findForSignIn(null)).isEmpty();
    assertThat(lookup.findForSignIn("")).isEmpty();
  }

  // ---- findRecipient -------------------------------------------------------

  @Test
  void recipient_ranksVerifiedThenRegisteredThenStub_oldestFirstWithinARank() {
    User stub = user("carol@x.com", UserEmailLookup.COMP_STUB_PASSWORD_PREFIX + "abc", false);
    User unverified = user("CAROL@x.com", "$2a$hash", false);
    User olderVerified = user("Carol@x.com", "$2a$hash", true);
    User newerVerified = user("carol@X.com", "$2a$hash", true);
    // The query returns oldest first.
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("carol@x.com"))
        .thenReturn(List.of(stub, unverified, olderVerified, newerVerified));

    // Typed in a spelling no account has, so oldest-first decides within the rank.
    assertThat(lookup.findRecipient("  carol@X.COM ")).contains(olderVerified);
  }

  /** Within a rank, the account stored exactly as typed wins over an older case variant. */
  @Test
  void recipient_withinARank_prefersTheExactSpellingOverTheOldest() {
    User olderVariant = user("Frank@x.com", "$2a$hash", true);
    User exact = user("frank@x.com", "$2a$hash", true);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("frank@x.com"))
        .thenReturn(List.of(olderVariant, exact));

    assertThat(lookup.findRecipient(" frank@x.com ")).contains(exact);
    assertThat(lookup.findRecipient("FRANK@x.com")).as("no exact match: the oldest").contains(olderVariant);
  }

  /** The exact spelling never lifts an account over a better rank. */
  @Test
  void recipient_theExactSpellingDoesNotBeatABetterRank() {
    User exactStub = user("gina@x.com", UserEmailLookup.COMP_STUB_PASSWORD_PREFIX + "abc", false);
    User verified = user("Gina@x.com", "$2a$hash", true);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("gina@x.com"))
        .thenReturn(List.of(exactStub, verified));

    assertThat(lookup.findRecipient("gina@x.com")).contains(verified);
  }

  @Test
  void recipient_unverifiedRegisteredBeatsAStub() {
    User stub = user("dan@x.com", UserEmailLookup.COMP_STUB_PASSWORD_PREFIX + "abc", false);
    User unverified = user("Dan@x.com", "$2a$hash", false);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("dan@x.com"))
        .thenReturn(List.of(stub, unverified));

    assertThat(lookup.findRecipient("dan@x.com")).contains(unverified);
  }

  @Test
  void recipient_aMatchOnlyTheDatabaseFoldsTogether_isDropped() {
    User alice = user("alice@x.com", "$2a$hash", true);
    when(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc("alıce@x.com")).thenReturn(List.of(alice));

    assertThat(lookup.findRecipient("alıce@x.com")).isEmpty();
  }

  @Test
  void recipient_blank_isEmpty() {
    assertThat(lookup.findRecipient(" ")).isEmpty();
    assertThat(lookup.findRecipient(null)).isEmpty();
  }

  private static User user(String email, String password, boolean verified) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setEmail(email);
    user.setPassword(password);
    user.setEmailVerified(verified);
    return user;
  }
}
