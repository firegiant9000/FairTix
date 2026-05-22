package com.fairtix.branding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BrandingValidatorTest {

  @Test
  void colorMustBeSevenCharHex() {
    assertThat(BrandingValidator.normalizeColor("#A1B2C3")).isEqualTo("#a1b2c3");
    assertThatThrownBy(() -> BrandingValidator.normalizeColor("red"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BrandingValidator.normalizeColor("#FFF"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void urlMustBeHttps() {
    assertThat(BrandingValidator.normalizeUrl("https://cdn.example.com/logo.svg"))
        .isEqualTo("https://cdn.example.com/logo.svg");
    assertThatThrownBy(() -> BrandingValidator.normalizeUrl("http://insecure"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BrandingValidator.normalizeUrl("javascript:alert(1)"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void statementDescriptorMustBeStripeShaped() {
    assertThat(BrandingValidator.normalizeStatementDescriptorSuffix("BLUENOTE NYC"))
        .isEqualTo("BLUENOTE NYC");
    assertThatThrownBy(() ->
        BrandingValidator.normalizeStatementDescriptorSuffix("has*illegal*chars"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
        BrandingValidator.normalizeStatementDescriptorSuffix("waaaay too long for stripe descriptor"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void senderNameRejectsControlChars() {
    assertThatThrownBy(() -> BrandingValidator.normalizeSenderName("Blue\nNote"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(BrandingValidator.normalizeSenderName("Blue Note NYC")).isEqualTo("Blue Note NYC");
  }

  @Test
  void blankIsNormalizedToNull() {
    assertThat(BrandingValidator.normalizeColor("")).isNull();
    assertThat(BrandingValidator.normalizeUrl("   ")).isNull();
    assertThat(BrandingValidator.normalizeEmail(null)).isNull();
  }
}
