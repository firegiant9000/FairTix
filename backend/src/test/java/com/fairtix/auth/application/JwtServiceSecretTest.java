package com.fairtix.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fairtix.auth.infrastructure.RefreshTokenRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The JWT signing secret is checked at startup (exposure gate (a), RM:327, decision
 * D5). JJWT only rejected a short key on first use, and the {@code .env.example}
 * placeholder booted.
 *
 * <p>Intended-red commit: {@code TBD}.
 */
class JwtServiceSecretTest {

  /** {@code .env.example:11}, which a copied file would carry into a deployment. */
  private static final String PLACEHOLDER = "replace_with_a_secret_at_least_64_characters_long";

  /** The value in {@code src/test/resources/application.properties}. */
  private static final String TEST_SECRET =
      "test-secret-key-for-unit-tests-only-not-for-production-use-min-32-chars";

  @Test
  void secretShorterThan32Bytes_failsStartup() {
    JwtService service = withSecret("a".repeat(31));

    assertThatThrownBy(service::validateSecret)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void placeholderSecret_failsStartup() {
    JwtService service = withSecret(PLACEHOLDER);

    assertThatThrownBy(service::validateSecret)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(".env.example");
  }

  @Test
  void placeholderWithSurroundingSpaces_failsStartup() {
    JwtService service = withSecret("  " + PLACEHOLDER + " ");

    assertThatThrownBy(service::validateSecret).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void blankSecret_failsStartup() {
    assertThatThrownBy(withSecret("")::validateSecret).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(withSecret(null)::validateSecret).isInstanceOf(IllegalStateException.class);
  }

  /** The error must never echo the secret into the logs. */
  @Test
  void failureMessage_doesNotContainTheSecret() {
    String shortSecret = "s3cr3t-value-xyz";

    assertThatThrownBy(withSecret(shortSecret)::validateSecret)
        .message().doesNotContain(shortSecret);
  }

  @Test
  void testSecret_passes() {
    assertThatCode(withSecret(TEST_SECRET)::validateSecret).doesNotThrowAnyException();
  }

  /** The limit is in UTF-8 bytes, which is what the HMAC key is built from. */
  @Test
  void multiByteSecret_isMeasuredInUtf8Bytes() {
    assertThatCode(withSecret("é".repeat(16))::validateSecret).doesNotThrowAnyException();
    assertThatThrownBy(withSecret("é".repeat(15) + "a")::validateSecret)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void multiByteSecret_signsAndVerifies() {
    JwtService service = withSecret("é".repeat(16));
    UUID userId = UUID.randomUUID();

    String token = service.generateToken(userId, "u@test.com", "USER");

    assertThat(service.extractAllClaims(token).get("userId", String.class)).isEqualTo(userId.toString());
  }

  private static JwtService withSecret(String secret) {
    JwtService service = new JwtService(mock(RefreshTokenRepository.class));
    ReflectionTestUtils.setField(service, "SECRET", secret);
    return service;
  }
}
