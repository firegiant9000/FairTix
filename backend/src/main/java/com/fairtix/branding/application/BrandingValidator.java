package com.fairtix.branding.application;

import java.util.regex.Pattern;

/**
 * Validation helpers for branding inputs. Kept as a separate class so the rules
 * (and their tests) live in one place rather than scattered across services.
 */
public final class BrandingValidator {

  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
  private static final Pattern STATEMENT_DESCRIPTOR = Pattern.compile("^[A-Za-z0-9 .\\-]{1,22}$");
  // Conservative URL whitelist: https only, no userinfo, optional path.
  private static final Pattern HTTPS_URL = Pattern.compile(
      "^https://[A-Za-z0-9.\\-]+(:\\d{1,5})?(/[A-Za-z0-9._~!$&'()*+,;=:@%/\\-]*)?$");
  // Local-part rules are loose by design — RFC 5322 is unhelpful here; the auth
  // flow already validates real emails via a verification round-trip.
  private static final Pattern EMAIL = Pattern.compile(
      "^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

  private BrandingValidator() {}

  public static String normalizeColor(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    if (!HEX_COLOR.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("primaryColor must be 7-char hex like #1A2B3C");
    }
    return trimmed.toLowerCase();
  }

  public static String normalizeUrl(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.length() > 1024 || !HTTPS_URL.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("URL must be https and under 1024 chars");
    }
    return trimmed;
  }

  public static String normalizeEmail(String value) {
    if (value == null) return null;
    String trimmed = value.trim().toLowerCase();
    if (trimmed.isEmpty()) return null;
    if (trimmed.length() > 255 || !EMAIL.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("Invalid email address");
    }
    return trimmed;
  }

  public static String normalizeSenderName(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.length() > 120) {
      throw new IllegalArgumentException("emailSenderName must be 120 chars or less");
    }
    // Strip control chars; reject anything fishy in From-line context.
    if (trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
      throw new IllegalArgumentException("emailSenderName may not contain control characters");
    }
    return trimmed;
  }

  public static String normalizeStatementDescriptorSuffix(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    if (!STATEMENT_DESCRIPTOR.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(
          "statementDescriptorSuffix must be 1-22 chars: letters, digits, spaces, '.', '-'");
    }
    return trimmed;
  }
}
