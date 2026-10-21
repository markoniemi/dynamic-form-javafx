package com.example.desktop.auth;

import com.example.desktop.api.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A logged-in user: the access token, and the roles read out of it.
 *
 * <p>The token is held in memory only and is not persisted. The authorization server issues no
 * refresh token to a public client, so a restart means logging in again.
 */
public class AuthSession {

  private final String accessToken;
  private final Set<String> roles;
  private final String username;

  AuthSession(String accessToken, Set<String> roles, String username) {
    this.accessToken = accessToken;
    this.roles = Set.copyOf(roles);
    this.username = username;
  }

  public String accessToken() {
    return accessToken;
  }

  /**
   * Who the backend considers the owner of anything this session submits.
   *
   * <p>Read from {@code preferred_username}, falling back to {@code sub}, which mirrors the
   * backend's {@code SecurityUtils}. If that rule ever changes server-side this must follow, or the
   * client will offer edits the server rejects.
   */
  public String username() {
    return username;
  }

  /** Bare role names from the token's {@code roles} claim, e.g. {@code ADMIN}. */
  public Set<String> roles() {
    return roles;
  }

  /**
   * Whether to offer the admin-only parts of the UI. This is a display decision only — every
   * admin endpoint is enforced by the backend, which is the authority.
   */
  public boolean isAdmin() {
    return roles.contains(Roles.ADMIN);
  }

  private static final String ROLE_PREFIX = "ROLE_";

  /**
   * Reads the roles from a JWT's payload.
   *
   * <p>The signature is deliberately <strong>not</strong> verified: this client never makes a trust
   * decision on the token's contents, it only decides which buttons to show, and the backend
   * re-validates every request. Verifying here would mean shipping the server's keys and gain
   * nothing.
   *
   * <p>Values are normalised to bare names. The oauth2-server image mints {@code roles} already
   * carrying the {@code ROLE_} prefix, and the backend's own converter accepts either form, so
   * neither can be relied on — stripping it here makes {@link #roles()} mean one thing whichever
   * the server sends.
   *
   * <p>Public because it is the only way to build a session: the login flow uses it, and so does a
   * UI test that needs a session without driving a browser. The constructor stays package-private
   * so every session's roles go through the normalisation above.
   */
  public static AuthSession fromToken(String accessToken, ObjectMapper mapper) {
    Set<String> roles = new LinkedHashSet<>();
    String username = "";
    String[] parts = accessToken.split("\\.");
    if (parts.length >= 2) {
      try {
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        JsonNode claims = mapper.readTree(new String(payload, StandardCharsets.UTF_8));
        for (String claim : new String[] {"roles", "cognito:groups"}) {
          for (JsonNode role : claims.path(claim)) {
            roles.add(stripPrefix(role.asText()));
          }
        }
        username = claims.path("preferred_username").asText(claims.path("sub").asText(""));
      } catch (Exception e) {
        // An unreadable payload only costs the user the admin UI, so start without roles rather
        // than failing a login that the server accepted.
        roles.clear();
        username = "";
      }
    }
    return new AuthSession(accessToken, roles, username);
  }

  private static String stripPrefix(String role) {
    return role.startsWith(ROLE_PREFIX) ? role.substring(ROLE_PREFIX.length()) : role;
  }
}
