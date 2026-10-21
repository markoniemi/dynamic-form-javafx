package com.example.desktop.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.json.JsonMapperFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The oauth2-server image mints {@code roles} already carrying the {@code ROLE_} prefix, while the
 * backend's own converter accepts either form — so neither can be assumed. These pin the
 * normalisation that makes {@code roles()} mean the same thing whichever arrives.
 */
class AuthSessionTest {

  @Test
  void stripsTheRolePrefixTheAuthServerActuallySends() {
    AuthSession session = session("{\"roles\":[\"ROLE_ADMIN\",\"ROLE_USER\"]}");

    assertEquals(Set.of("ADMIN", "USER"), session.roles());
    assertTrue(session.isAdmin());
  }

  @Test
  void acceptsBareRolesToo() {
    AuthSession session = session("{\"roles\":[\"USER\"]}");

    assertEquals(Set.of("USER"), session.roles());
    assertFalse(session.isAdmin());
  }

  @Test
  void readsCognitoGroupsAsWell() {
    assertTrue(session("{\"cognito:groups\":[\"ADMIN\"]}").isAdmin());
  }

  @Test
  void aTokenThatIsNotAJwtYieldsNoRolesRatherThanFailingTheLogin() {
    AuthSession session = AuthSession.fromToken("not-a-jwt", JsonMapperFactory.create());

    assertTrue(session.roles().isEmpty());
    assertFalse(session.isAdmin());
    assertEquals("not-a-jwt", session.accessToken());
    assertEquals("", session.username());
  }

  @Test
  void aJwtWhosePayloadIsNotReadableYieldsNoRolesRatherThanFailingTheLogin() {
    // Three parts, so the decode is attempted and the catch block actually runs — the earlier
    // version of this test called the constructor and never exercised it.
    AuthSession session =
        AuthSession.fromToken("header.!!!not-base64!!!.signature", JsonMapperFactory.create());

    assertTrue(session.roles().isEmpty());
    assertFalse(session.isAdmin());
  }

  @Test
  void usernameComesFromPreferredUsernameAndFallsBackToSub() {
    assertEquals("alice", session("{\"preferred_username\":\"alice\",\"sub\":\"uuid\"}").username());
    assertEquals("uuid", session("{\"sub\":\"uuid\"}").username());
  }

  /** Builds an unsigned JWT-shaped token with the given payload; the signature is never checked. */
  private static AuthSession session(String claimsJson) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8))
            + ".signature";
    return AuthSession.fromToken(token, JsonMapperFactory.create());
  }
}
