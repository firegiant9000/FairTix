package com.fairtix.auth.application;

import com.fairtix.auth.application.RegistrationPolicy.Decision;
import com.fairtix.auth.application.RegistrationPolicy.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registration mode (RM:323). Red on commit {@code 4bf8642}: registration was open to
 * anyone, with no setting to close it.
 */
class RegistrationPolicyTest {

  @Test
  void defaultMode_isDisabled() {
    // The @Value default, not a property file: the test file shadows the main one.
    new ApplicationContextRunner()
        .withUserConfiguration(RegistrationPolicy.class)
        .run(ctx -> {
          RegistrationPolicy policy = ctx.getBean(RegistrationPolicy.class);
          assertThat(policy.mode()).isEqualTo(Mode.DISABLED);
          assertThat(policy.check("anyone@test.com").allowed()).isFalse();
        });
  }

  @Test
  void disabled_blocksEveryone() {
    assertThat(new RegistrationPolicy("disabled", "a@test.com").check("a@test.com"))
        .isEqualTo(new Decision(false, false));
  }

  @Test
  void open_allowsAnyone_notPreVerified() {
    assertThat(new RegistrationPolicy("open", "").check("a@test.com"))
        .isEqualTo(new Decision(true, false));
  }

  @Test
  void allowlist_match_isPreVerified() {
    assertThat(new RegistrationPolicy("allowlist", "a@test.com,b@test.com").check("b@test.com"))
        .isEqualTo(new Decision(true, true));
  }

  @Test
  void allowlist_miss_isBlocked() {
    assertThat(new RegistrationPolicy("allowlist", "a@test.com").check("c@test.com").allowed())
        .isFalse();
  }

  @Test
  void allowlist_normalisesCaseAndBlanks() {
    RegistrationPolicy policy = new RegistrationPolicy("ALLOWLIST", " Demo@FairTix.demo , ,");
    assertThat(policy.check("demo@fairtix.demo").allowed()).isTrue();
    assertThat(policy.check(" DEMO@fairtix.DEMO ").allowed()).isTrue();
    assertThat(policy.check("").allowed()).isFalse();
  }

  /**
   * Java folds the Kelvin sign (U+212A) to {@code k}; PostgreSQL's case-insensitive
   * duplicate check may not, so a lookalike would get a second pre-verified account.
   * Red on commit {@code <red3>} (review round 2).
   */
  @Test
  void allowlist_nonAscii_isBlocked() {
    RegistrationPolicy policy = new RegistrationPolicy("allowlist", "kim@x.com");
    assertThat(policy.check("Kim@x.com").allowed()).isFalse();
    assertThat(policy.check("kim@x.com").allowed()).isTrue();
  }

  @Test
  void allowlist_matchesExactAddressesOnly() {
    RegistrationPolicy policy = new RegistrationPolicy("allowlist", "@fairtix.demo");
    assertThat(policy.check("x@fairtix.demo").allowed()).isFalse();
  }

  @Test
  void allowlist_empty_blocksAll() {
    RegistrationPolicy policy = new RegistrationPolicy("allowlist", "");
    assertThat(policy.check("a@test.com").allowed()).isFalse();
    assertThat(policy.check("").allowed()).isFalse();
  }

  @Test
  void unknownMode_failsStartup() {
    assertThatThrownBy(() -> new RegistrationPolicy("invite", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fairtix.registration.mode");
  }

  /** The deployed defaults, read from the main files on disk (the test classpath shadows them). */
  @Test
  void mainFile_defaultsToDisabled() throws IOException {
    Path resources = Path.of("src/main/resources");
    assertThat(load(resources.resolve("application.properties"))
        .getProperty("fairtix.registration.mode"))
        .isEqualTo("${FAIRTIX_REGISTRATION_MODE:disabled}");
    for (String profile : new String[] {"staging", "prod"}) {
      assertThat(load(resources.resolve("application-" + profile + ".properties"))
          .getProperty("fairtix.registration.mode"))
          .as(profile + " must inherit the disabled default")
          .isNull();
    }
    assertThat(load(resources.resolve("application-dev.properties"))
        .getProperty("fairtix.registration.mode"))
        .isEqualTo("open");
  }

  static Properties load(Path file) throws IOException {
    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(file)) {
      props.load(reader);
    }
    return props;
  }
}
