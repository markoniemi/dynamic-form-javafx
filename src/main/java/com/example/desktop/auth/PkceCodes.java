package com.example.desktop.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * One login attempt's PKCE secrets (RFC 7636) and its CSRF {@code state}.
 *
 * @param verifier kept by the client and sent only in the token request
 * @param challenge the S256 hash of the verifier, sent in the authorization request
 * @param state echoed back on the callback, so a response from elsewhere can be rejected
 */
public record PkceCodes(String verifier, String challenge, String state) {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  /** Draws fresh secrets. 32 random bytes encode to 43 characters, RFC 7636's minimum length. */
  public static PkceCodes generate() {
    String verifier = randomString(32);
    return new PkceCodes(verifier, challengeFor(verifier), randomString(16));
  }

  private static String randomString(int bytes) {
    byte[] buffer = new byte[bytes];
    RANDOM.nextBytes(buffer);
    return ENCODER.encodeToString(buffer);
  }

  private static String challengeFor(String verifier) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return ENCODER.encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }
}
