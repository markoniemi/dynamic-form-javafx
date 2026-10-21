package com.example.desktop.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The callback server is where a login goes wrong in ways the user has to understand: a cancelled
 * consent screen, a forged state, a port another process already holds.
 */
class LoopbackCallbackServerTest {

  @Test
  void returnsTheCodeFromTheCallbackRequest() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      callAsync(server.redirectUri() + "?code=abc123&state=s1");

      assertEquals("abc123", server.awaitCode("s1", Duration.ofSeconds(5)));
    }
  }

  @Test
  void rejectsAMismatchedState() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      callAsync(server.redirectUri() + "?code=abc123&state=forged");

      Exception e =
          assertThrows(Exception.class, () -> server.awaitCode("s1", Duration.ofSeconds(5)));
      assertTrue(e.getMessage().toLowerCase().contains("state"), e.getMessage());
    }
  }

  @Test
  void reportsTheErrorWhenTheUserCancelsInsteadOfWaitingForACode() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      callAsync(server.redirectUri() + "?error=access_denied&state=s1");

      Exception e =
          assertThrows(Exception.class, () -> server.awaitCode("s1", Duration.ofSeconds(5)));
      assertTrue(e.getMessage().contains("access_denied"), e.getMessage());
    }
  }

  @Test
  void aBusyPortGivesAMessageNamingThePortNotABareBindException() throws Exception {
    try (LoopbackCallbackServer first = new LoopbackCallbackServer(0)) {
      int taken = first.port();

      Exception e = assertThrows(Exception.class, () -> new LoopbackCallbackServer(taken));

      assertTrue(e.getMessage().contains(String.valueOf(taken)), e.getMessage());
    }
  }

  /** Stands in for the browser following the authorization server's redirect. */
  private static void callAsync(String url) {
    HttpClient.newHttpClient()
        .sendAsync(
            HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
