package com.fairtix.auth.application;

import com.fairtix.auth.domain.RefreshToken;
import com.fairtix.auth.infrastructure.RefreshTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

@Service
public class JwtService {

  @Value("${JWT_SECRET}")
  private String SECRET;

  @Value("${app.jwt.refresh-expiry-days:7}")
  private int refreshExpiryDays;

  private static final long ACCESS_EXPIRATION_MS = 1000 * 60 * 15; // 15 minutes

  /** HS256's minimum key size (RFC 7518 §3.2), which JJWT also enforces. */
  private static final int MIN_SECRET_BYTES = 32;

  /** The value shipped in {@code .env.example}; a copied file must not boot. */
  private static final String ENV_EXAMPLE_PLACEHOLDER = "replace_with_a_secret_at_least_64_characters_long";

  private final RefreshTokenRepository refreshTokenRepository;
  private final SecureRandom secureRandom = new SecureRandom();

  public JwtService(RefreshTokenRepository refreshTokenRepository) {
    this.refreshTokenRepository = refreshTokenRepository;
  }

  /**
   * Fails startup on a weak secret, rather than on the first login (JJWT checks the
   * key length lazily). The messages never include the secret.
   */
  @PostConstruct
  void validateSecret() {
    if (SECRET == null || SECRET.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (UTF-8) for HMAC-SHA signing");
    }
    if (ENV_EXAMPLE_PLACEHOLDER.equals(SECRET.trim())) {
      throw new IllegalStateException(
          "JWT_SECRET is still the .env.example placeholder; generate a random secret");
    }
  }

  private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  public String generateToken(UUID userId, String email, String role) {
    return Jwts.builder()
        .subject(email)
        .claim("userId", userId.toString())
        .claim("role", role)
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRATION_MS))
        .signWith(getSigningKey())
        .compact();
  }

  @Transactional
  public String generateRefreshToken(UUID userId) {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String hash = sha256(rawToken);

    RefreshToken entity = new RefreshToken();
    entity.setUserId(userId);
    entity.setTokenHash(hash);
    entity.setExpiresAt(Instant.now().plus(refreshExpiryDays, ChronoUnit.DAYS));
    refreshTokenRepository.save(entity);

    return rawToken;
  }

  public String hashRefreshToken(String rawToken) {
    return sha256(rawToken);
  }

  public Claims extractAllClaims(String token) {
    return Jwts.parser()
        .verifyWith(getSigningKey())
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  public String extractEmail(String token) {
    return extractAllClaims(token).getSubject();
  }

  public boolean isTokenValid(String token) {
    try {
      Claims claims = extractAllClaims(token);
      return claims.getExpiration().after(new Date());
    } catch (Exception e) {
      return false;
    }
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
