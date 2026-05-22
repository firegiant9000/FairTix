package com.fairtix.organizations.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EinCipherTest {

  private final EinCipher cipher = new EinCipher("test-key-material-stable");

  @Test
  void roundTrips() {
    String plain = "12-3456789";
    String ct = cipher.encrypt(plain);
    assertThat(ct).isNotEqualTo(plain);
    assertThat(cipher.decrypt(ct)).isEqualTo(plain);
  }

  @Test
  void nonDeterministicCiphertext() {
    String plain = "98-7654321";
    String a = cipher.encrypt(plain);
    String b = cipher.encrypt(plain);
    // Random IV per encryption — same plaintext must produce different ciphertext.
    assertThat(a).isNotEqualTo(b);
    assertThat(cipher.decrypt(a)).isEqualTo(plain);
    assertThat(cipher.decrypt(b)).isEqualTo(plain);
  }

  @Test
  void nullPassthrough() {
    assertThat(cipher.encrypt(null)).isNull();
    assertThat(cipher.decrypt(null)).isNull();
  }
}
