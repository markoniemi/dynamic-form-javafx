package com.example.desktop.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PkceCodesTest {

  @Test
  void challengeIsTheUnpaddedBase64UrlSha256OfTheVerifier() throws Exception {
    PkceCodes codes = PkceCodes.generate();

    byte[] expected =
        MessageDigest.getInstance("SHA-256")
            .digest(codes.verifier().getBytes(StandardCharsets.US_ASCII));
    assertEquals(
        Base64.getUrlEncoder().withoutPadding().encodeToString(expected), codes.challenge());
    assertFalse(codes.challenge().contains("="));
  }

  @Test
  void verifierAndStateAreFreshEachTimeAndLongEnoughForRfc7636() {
    PkceCodes a = PkceCodes.generate();
    PkceCodes b = PkceCodes.generate();

    assertNotEquals(a.verifier(), b.verifier());
    assertNotEquals(a.state(), b.state());
    assertTrue(a.verifier().length() >= 43 && a.verifier().length() <= 128);
  }
}
