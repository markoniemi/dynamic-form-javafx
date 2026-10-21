package com.example.desktop.api;

/**
 * Role names.
 *
 * <p>The JWT's {@code roles} claim carries <em>bare</em> values; the backend's
 * {@code RoleClaimsConverter} adds the {@code ROLE_} prefix on its side. So a client inspecting a
 * token compares against {@link #ADMIN}, while {@link #ROLE_ADMIN} is what the server's authorities
 * and {@code @PreAuthorize} expressions look like — useful only when reading the backend's own
 * messages.
 */
public final class Roles {

  /** Bare claim value, as it appears in the token. */
  public static final String ADMIN = "ADMIN";

  /** Bare claim value, as it appears in the token. */
  public static final String USER = "USER";

  /** The authority the backend derives from {@link #ADMIN}. */
  public static final String ROLE_ADMIN = "ROLE_ADMIN";

  private Roles() {}
}
