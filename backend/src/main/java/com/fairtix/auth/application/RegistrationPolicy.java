package com.fairtix.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may create an account through {@code POST /auth/register} (RM:323).
 *
 * <ul>
 *   <li>{@code open}: anyone (dev only);
 *   <li>{@code allowlist}: only the exact addresses in {@code fairtix.registration.allowlist}
 *       (comma-separated, case-insensitive; no domain wildcards). The operator vetted
 *       them, so they start verified;
 *   <li>{@code disabled}: nobody. This is the default when the property is missing,
 *       so a deploy that forgets it fails closed.
 * </ul>
 */
@Component
public class RegistrationPolicy {

  public enum Mode { OPEN, ALLOWLIST, DISABLED }

  /** {@code preVerified}: the operator vetted this exact address (allowlist mode). */
  public record Decision(boolean allowed, boolean preVerified) {}

  private static final Decision BLOCKED = new Decision(false, false);

  private final Mode mode;
  private final Set<String> allowlist;

  public RegistrationPolicy(
      @Value("${fairtix.registration.mode:disabled}") String mode,
      @Value("${fairtix.registration.allowlist:}") String allowlist) {
    this.mode = parseMode(mode);
    this.allowlist = Arrays.stream(allowlist.split(","))
        .map(RegistrationPolicy::normalise)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  public Mode mode() {
    return mode;
  }

  public Decision check(String email) {
    return switch (mode) {
      case OPEN -> new Decision(true, false);
      case DISABLED -> BLOCKED;
      case ALLOWLIST -> {
        String normalised = normalise(email);
        yield !normalised.isEmpty() && allowlist.contains(normalised)
            ? new Decision(true, true)
            : BLOCKED;
      }
    };
  }

  private static Mode parseMode(String value) {
    try {
      return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "fairtix.registration.mode must be open, allowlist or disabled, not '" + value + "'");
    }
  }

  private static String normalise(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }
}
