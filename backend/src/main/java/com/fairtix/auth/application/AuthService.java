package com.fairtix.auth.application;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fairtix.audit.application.AuditService;
import com.fairtix.notifications.application.NotificationPreferenceService;
import com.fairtix.users.domain.User;
import com.fairtix.users.dto.LoginRequest;
import com.fairtix.users.dto.RegisterRequest;
import com.fairtix.users.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final LoginAttemptService loginAttemptService;
  private final RecaptchaService recaptchaService;
  private final NotificationPreferenceService notificationPreferenceService;
  private final EmailVerificationService emailVerificationService;
  private final AuditService auditService;

  private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
  private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
  private static final Pattern DIGIT = Pattern.compile("[0-9]");
  private static final Pattern SPECIAL = Pattern.compile("[^a-zA-Z0-9]");
  private static final int MIN_PASSWORD_LENGTH = 8;

  public AuthService(UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      LoginAttemptService loginAttemptService,
      RecaptchaService recaptchaService,
      NotificationPreferenceService notificationPreferenceService,
      EmailVerificationService emailVerificationService,
      AuditService auditService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.loginAttemptService = loginAttemptService;
    this.recaptchaService = recaptchaService;
    this.notificationPreferenceService = notificationPreferenceService;
    this.emailVerificationService = emailVerificationService;
    this.auditService = auditService;
  }

  @Transactional
  public String register(RegisterRequest request) {
    validatePasswordStrength(request.password());

    if (userRepository.findByEmail(request.email()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
    }

    User user = new User();
    user.setEmail(request.email());
    user.setPassword(passwordEncoder.encode(request.password()));

    userRepository.save(user);
    notificationPreferenceService.createDefault(user.getId());
    auditService.log(user.getId(), "USER_REGISTERED", "USER", user.getId(), null);

    try {
      emailVerificationService.sendVerificationEmail(user);
    } catch (Exception e) {
      log.error("Failed to send verification email to={} error={}", user.getEmail(), e.getMessage());
      // Don't fail registration if email delivery fails
    }

    return jwtService.generateToken(
        user.getId(),
        user.getEmail(),
        user.getRole().name());
  }

  public String login(LoginRequest request) {
    String email = request.email();

    // Check lockout before anything else
    if (loginAttemptService.isLocked(email)) {
      throw new AccountLockedException(loginAttemptService.getRemainingLockoutSeconds(email));
    }

    // After 3 failures, captcha is required; validate before password check
    long failedAttempts = loginAttemptService.getAttemptCount(email);
    if (recaptchaService.isCaptchaRequired(failedAttempts)) {
      recaptchaService.assertValidToken(request.recaptchaToken());
    }

    User user = userRepository.findByEmail(email).orElse(null);

    if (user == null || user.isDeleted()
        || !passwordEncoder.matches(request.password(), user.getPassword())) {
      long failures = loginAttemptService.recordFailure(email);
      auditFailedLogin(user, failures);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    // Successful login — reset attempts
    loginAttemptService.resetAttempts(email);
    auditService.log(user.getId(), "USER_LOGIN", "USER", user.getId(), null);

    return jwtService.generateToken(
        user.getId(),
        user.getEmail(),
        user.getRole().name());
  }

  /**
   * Audits a failed login against the account when the email matched one, else
   * with no user. Details carry counts only, never the submitted email or
   * password. {@code log} is REQUIRES_NEW, so the row survives the 401; a failed
   * audit write is logged and never turns the 401 into a 500.
   */
  private void auditFailedLogin(User user, long failures) {
    UUID userId = user != null ? user.getId() : null;
    int max = loginAttemptService.getMaxAttempts();
    try {
      auditService.log(userId, "LOGIN_FAILED", "USER", userId,
          "failed_attempts=" + failures + " max=" + max);
      if (failures == max) {
        auditService.log(userId, "ACCOUNT_LOCKED", "USER", userId,
            "failed_attempts=" + failures);
      }
    } catch (RuntimeException e) {
      log.warn("Failed-login audit write failed: {}", e.getClass().getSimpleName());
    }
  }

  private void validatePasswordStrength(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new WeakPasswordException(
          "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
    }
    if (!UPPERCASE.matcher(password).find()) {
      throw new WeakPasswordException("Password must contain at least one uppercase letter");
    }
    if (!LOWERCASE.matcher(password).find()) {
      throw new WeakPasswordException("Password must contain at least one lowercase letter");
    }
    if (!DIGIT.matcher(password).find()) {
      throw new WeakPasswordException("Password must contain at least one digit");
    }
    if (!SPECIAL.matcher(password).find()) {
      throw new WeakPasswordException("Password must contain at least one special character");
    }
  }
}
