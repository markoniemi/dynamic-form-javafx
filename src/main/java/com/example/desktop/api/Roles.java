package com.example.desktop.api;

/**
 * Role names, in the bare form the client works with.
 *
 * <p>The JWT's {@code roles} claim is not dependable on this point: the oauth2-server image mints
 * values already carrying the {@code ROLE_} prefix, while the backend's {@code RoleClaimsConverter}
 * adds the prefix only when it is missing, so both forms are valid. {@code AuthSession} normalises
 * to the bare name, and that is what {@link #ADMIN} and {@link #USER} are for. {@link #ROLE_ADMIN}
 * is the authority the backend's {@code @PreAuthorize} expressions use — useful only when reading
 * the backend's own messages.
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
