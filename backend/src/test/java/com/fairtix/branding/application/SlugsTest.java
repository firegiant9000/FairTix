package com.fairtix.branding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SlugsTest {

  @Test
  void slugifyLowerCasesAndDashesPunctuation() {
    assertThat(Slugs.slugify("Summer Music Festival 2026!")).isEqualTo("summer-music-festival-2026");
  }

  @Test
  void slugifyStripsDiacritics() {
    assertThat(Slugs.slugify("Café Olé")).isEqualTo("cafe-ole");
  }

  @Test
  void requireValidRejectsReserved() {
    assertThatThrownBy(() -> Slugs.requireValid("api"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requireValidRejectsUpperCase() {
    assertThatThrownBy(() -> Slugs.requireValid("Foo-Bar"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requireValidAcceptsKebabCase() {
    Slugs.requireValid("blue-note-nyc");
  }
}
