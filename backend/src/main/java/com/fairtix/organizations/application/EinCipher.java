package com.fairtix.organizations.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope for EIN-at-rest.
 *
 * <p>Key material is loaded from {@code fairtix.ein.encryption-key} (passphrase
 * or 32-byte base64). For dev/test we derive from a fixed default so Flyway +
 * tests work out of the box; production must set the property to a 256-bit
 * value from the secrets store. Loss of the key means historical EIN ciphertext
 * is unrecoverable — that is intentional and documented in the runbook.
 */
@Component
public class EinCipher {

  private static final Logger log = LoggerFactory.getLogger(EinCipher.class);
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final String DEV_DEFAULT = "fairtix-dev-only-do-not-use-in-prod";

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public EinCipher(@Value("${fairtix.ein.encryption-key:}") String configured) {
    String material = (configured == null || configured.isBlank()) ? DEV_DEFAULT : configured;
    if (material.equals(DEV_DEFAULT)) {
      log.warn("fairtix.ein.encryption-key not set; using DEV default. Do not run this in production.");
    }
    this.key = deriveKey(material);
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) return null;
    try {
      byte[] iv = new byte[IV_LENGTH];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ct, 0, combined, iv.length, ct.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt EIN", e);
    }
  }

  public String decrypt(String ciphertext) {
    if (ciphertext == null) return null;
    try {
      byte[] combined = Base64.getDecoder().decode(ciphertext);
      byte[] iv = new byte[IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
      byte[] ct = new byte[combined.length - IV_LENGTH];
      System.arraycopy(combined, IV_LENGTH, ct, 0, ct.length);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt EIN", e);
    }
  }

  private static SecretKeySpec deriveKey(String material) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] raw = sha.digest(material.getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(raw, "AES");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to derive EIN key", e);
    }
  }
}
