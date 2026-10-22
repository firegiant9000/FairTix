package com.fairtix.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Flyway migration creates a login (RM:324: "confirm that no migration seeds a
 * usable admin"). Verified 2026-10-21 over V1–V47: no user inserts, no password
 * hashes, no role grants. This is a guard, not a red test: it passed from the start
 * and fails if a future migration adds a seeded account. The first admin is created
 * by the runbook step in {@code DEPLOY.md}.
 */
class NoSeededAdminTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

  private static final List<Pattern> FORBIDDEN = List.of(
      Pattern.compile("insert\\s+into\\s+(public\\.)?users\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("update\\s+(public\\.)?users\\s+set[^;]*\\b(role|password)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
      Pattern.compile("\\$2[aby]\\$\\d{2}\\$"), // bcrypt
      Pattern.compile("\\{(bcrypt|noop|pbkdf2|scrypt|argon2)}", Pattern.CASE_INSENSITIVE));

  @Test
  void noMigrationSeedsALogin() throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.list(MIGRATIONS)) {
      files = s.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
    }
    assertThat(files).as("migrations found").hasSizeGreaterThanOrEqualTo(47);

    for (Path file : files) {
      String sql = Files.readString(file);
      for (Pattern p : FORBIDDEN) {
        assertThat(p.matcher(sql).find())
            .as(file.getFileName() + " matches " + p.pattern())
            .isFalse();
      }
    }
  }
}
