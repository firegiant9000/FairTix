package com.fairtix.users.application;

import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds accounts by email, ignoring case. Since V57 {@code users.email} is unique ignoring case
 * (on {@code upper(email)}), so at most one account matches. Each lookup still states what it
 * would do if several did, as a defence: V57 refuses to run on a database that has such
 * accounts, but nothing stops an operator from dropping the index.
 */
@Service
public class UserEmailLookup {

  /** Password prefix of the stub accounts comps create; never a valid password hash. */
  public static final String COMP_STUB_PASSWORD_PREFIX = "comp:";

  private static final Logger log = LoggerFactory.getLogger(UserEmailLookup.class);

  private final UserRepository users;

  public UserEmailLookup(UserRepository users) {
    this.users = users;
  }

  /**
   * The account that should receive something sent to {@code email} (a comp, a transfer),
   * ignoring case. When several match: a verified registered account, then any registered
   * account, then a comp stub; within each, the account stored exactly as typed (after
   * trimming), then the oldest.
   */
  public Optional<User> findRecipient(String email) {
    if (email == null || email.isBlank()) {
      return Optional.empty();
    }
    String typed = email.trim();
    String key = normalize(typed);
    List<User> matches = sameAddress(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc(key), key);
    // min keeps the first of equal elements, so the query's oldest-first order breaks the
    // remaining ties.
    Optional<User> chosen = matches.stream().min(
        Comparator.comparingInt(UserEmailLookup::recipientRank)
            .thenComparing(u -> !typed.equals(u.getEmail())));
    if (matches.size() > 1) {
      log.warn("Recipient email matches {} accounts ignoring case; using user {}",
          matches.size(), chosen.map(User::getId).orElse(null));
    }
    return chosen;
  }

  /**
   * The account to sign in as (login, password reset): ignoring case when exactly one account
   * matches, otherwise only the exact match, so an ambiguous address never reaches someone
   * else's account. The address is not trimmed: the login lockout is keyed on it as typed
   * (lower-cased), and a lookup that trimmed would let padded variants count separately.
   */
  public Optional<User> findForSignIn(String email) {
    if (email == null || email.isEmpty()) {
      return Optional.empty();
    }
    List<User> matches = sameAddress(users.findAllByEmailIgnoreCaseOrderByCreatedAtAscIdAsc(email), email);
    if (matches.size() == 1) {
      return Optional.of(matches.get(0));
    }
    return matches.stream().filter(u -> email.equals(u.getEmail())).findFirst();
  }

  /**
   * The case-folding the login lockout ({@code LoginAttemptService}) and the password reset
   * rate limit key on, and so the only one these lookups may honour.
   */
  public static String normalize(String email) {
    return email.toLowerCase(Locale.ROOT);
  }

  /**
   * The database compares {@code upper(email)}, which folds more than Java does: under a
   * glibc locale {@code upper('ı')} is {@code I} and {@code upper('ſ')} is {@code S}. A
   * spelling the database matches but {@link #normalize} does not would get a lockout counter
   * of its own, a fresh set of password guesses per spelling. Keep only the accounts whose
   * address normalizes to the same key as the one typed.
   */
  private static List<User> sameAddress(List<User> matches, String typed) {
    String key = normalize(typed);
    return matches.stream()
        .filter(u -> u.getEmail() != null && normalize(u.getEmail()).equals(key))
        .toList();
  }

  /** 0 verified registered account, 1 unverified registered account, 2 comp stub. */
  static int recipientRank(User user) {
    if (isCompStub(user)) return 2;
    return user.isEmailVerified() ? 0 : 1;
  }

  public static boolean isCompStub(User user) {
    return user.getPassword() != null && user.getPassword().startsWith(COMP_STUB_PASSWORD_PREFIX);
  }
}
