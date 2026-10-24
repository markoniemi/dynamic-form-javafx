package com.example.desktop.api;

/**
 * Obtains a new access token after the backend has rejected the current one.
 *
 * <p>Implemented by whatever owns the login — {@code App} runs the PKCE flow again — so that {@link
 * ApiClient} can recover from an expired token without knowing anything about OAuth2, a browser or
 * the session object the rest of the application reads.
 *
 * <p>Called on the thread that met the 401, never on the JavaFX application thread, and never by
 * more than one thread at a time: {@link ApiClient} serializes the calls so a burst of 401s cannot
 * open a browser window each.
 */
public interface Reauthenticator {

  /**
   * Logs in again.
   *
   * @return the new access token, or null if the user could not be logged in. Returning null, or
   *     throwing, leaves the original 401 to reach the caller — which is the readable outcome,
   *     since a failed login is not something a pane can do anything about.
   */
  String reauthenticate();
}
