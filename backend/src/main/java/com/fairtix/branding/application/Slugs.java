package com.fairtix.branding.application;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Shared slug utilities for organizations, events, and historical URL redirects.
 * Slugs are lower-kebab, ASCII-only, and bounded in length. They are intended
 * to appear in URLs and must not collide with route literals.
 */
public final class Slugs {

  private static final Pattern NON_ASCII_ALPHANUM = Pattern.compile("[^a-z0-9]+");
  private static final Pattern RESERVED = Pattern.compile(
      "^(api|admin|auth|login|signup|logout|organizer|embed|sitemap|robots|public|static)$");
  // Strict validation for slugs already chosen by the user; differs from the
  // slugify above (which forgives whitespace, punctuation, capitals).
  private static final Pattern VALID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

  private Slugs() {}

  public static String slugify(String input) {
    if (input == null) return null;
    String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
        .replaceAll("\\p{M}+", "");
    String lowered = normalized.toLowerCase();
    String cleaned = NON_ASCII_ALPHANUM.matcher(lowered).replaceAll("-")
        .replaceAll("(^-+|-+$)", "");
    if (cleaned.length() > 140) cleaned = cleaned.substring(0, 140);
    return cleaned.isEmpty() ? null : cleaned;
  }

  public static void requireValid(String slug) {
    if (slug == null || slug.isBlank()) {
      throw new IllegalArgumentException("slug is required");
    }
    if (slug.length() > 140 || !VALID.matcher(slug).matches()) {
      throw new IllegalArgumentException("slug must be lower kebab-case, ASCII alphanumerics");
    }
    if (RESERVED.matcher(slug).matches()) {
      throw new IllegalArgumentException("slug '" + slug + "' is reserved");
    }
  }
}
