package com.example.desktop.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.desktop.json.JsonMapperFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the login does with documents that are not the ones it expects.
 *
 * <p>{@code PkceLoginFlowIT} covers the happy path against the real authorization server. This
 * stands a local {@link HttpServer} in for both the backend and the issuer, so a trimmed discovery
 * document or an error answered with status 200 needs no container.
 */
class PkceLoginFlowTest {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
    // The flow asks the backend which issuer to trust; point it back at this same server.
    stub(PkceLoginFlow.ISSUER_URI_PATH, "text/plain", baseUrl);
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  @Test
  void metadataWithoutAnAuthorizationEndpointSaysSoRatherThanThrowingNpe() {
    // A wrong issuer URL, or an auth server that answers an error document with status 200.
    stubMetadata("{\"token_endpoint\":\"" + baseUrl + "/oauth2/token\"}");

    Exception e = assertThrows(Exception.class, () -> flow(browserMustNotOpen()).login());

    assertFalse(e instanceof NullPointerException, "a bare NPE names nothing the user can act on");
    assertTrue(e.getMessage().contains("authorization_endpoint"), e.getMessage());
  }

  @Test
  void metadataWithoutATokenEndpointSaysSo() {
    stubMetadata("{\"authorization_endpoint\":\"" + baseUrl + "/oauth2/authorize\"}");

    Exception e = assertThrows(Exception.class, () -> flow(browserMustNotOpen()).login());

    assertFalse(e instanceof NullPointerException, "a bare NPE names nothing the user can act on");
    assertTrue(e.getMessage().contains("token_endpoint"), e.getMessage());
  }

  @Test
  void aTokenResponseWithoutAnAccessTokenSaysSo() {
    // The server answered 200 with something that is not a token response.
    stubMetadata(bothEndpoints());
    stub("/oauth2/token", "application/json", "{\"error\":\"invalid_grant\"}");

    Exception e = assertThrows(Exception.class, () -> flow(completeTheCallback()).login());

    assertFalse(e instanceof NullPointerException, "a bare NPE names nothing the user can act on");
    assertTrue(e.getMessage().contains("access_token"), e.getMessage());
  }

  @Test
  void theMessageDoesNotCarryTheDocumentItCameFrom() {
    // DesktopApp.reportFatal puts this message verbatim into a modal alert, so the whole document
    // would
    // be on screen — and a token response that answers 200 with an id_token but no access_token
    // would put that JWT there. The project rule is that no token appears in an exception message.
    stubMetadata(bothEndpoints());
    stub(
        "/oauth2/token",
        "application/json",
        "{\"id_token\":\"eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.sig\",\"scope\":\"openid\"}");

    Exception e = assertThrows(Exception.class, () -> flow(completeTheCallback()).login());

    assertTrue(e.getMessage().contains("access_token"), e.getMessage());
    assertFalse(e.getMessage().contains("eyJ"), "no token value in the message: " + e.getMessage());
    assertFalse(e.getMessage().contains("{"), "no document body either: " + e.getMessage());
    // Naming the fields it did carry is safe and is what tells the user which document is wrong.
    assertTrue(e.getMessage().contains("id_token"), e.getMessage());
  }

  private String bothEndpoints() {
    return "{\"authorization_endpoint\":\""
        + baseUrl
        + "/oauth2/authorize\",\"token_endpoint\":\""
        + baseUrl
        + "/oauth2/token\"}";
  }

  private PkceLoginFlow flow(BrowserLauncher browser) {
    return new PkceLoginFlow(baseUrl, browser, JsonMapperFactory.create());
  }

  private void stubMetadata(String json) {
    stub("/.well-known/openid-configuration", "application/json", json);
  }

  private void stub(String path, String contentType, String body) {
    server.createContext(path, respondWith(contentType, body));
  }

  private static HttpHandler respondWith(String contentType, String body) {
    return (HttpExchange exchange) -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
      exchange.close();
    };
  }

  /** For the cases that must fail during discovery, before any browser is involved. */
  private static BrowserLauncher browserMustNotOpen() {
    return uri -> fail("discovery must fail before the browser opens, but it got " + uri);
  }

  /**
   * Stands in for the browser and the authorization server together: reads the {@code state} out of
   * the authorization URL and calls the real loopback callback with a code, which is what lets the
   * flow reach the token exchange.
   */
  private static BrowserLauncher completeTheCallback() {
    return uri -> {
      String state = queryParam(uri, "state");
      String callback =
          "http://localhost:"
              + PkceLoginFlow.CALLBACK_PORT
              + "/callback?code=test-code&state="
              + state;
      try {
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(callback)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        // The code has already reached the callback server by the time the response is read, and
        // whether the browser gets a readable page is what Task 3 of this plan is about.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }

  private static String queryParam(URI uri, String name) {
    for (String pair : uri.getQuery().split("&")) {
      int split = pair.indexOf('=');
      if (split > 0 && pair.substring(0, split).equals(name)) {
        return pair.substring(split + 1);
      }
    }
    throw new IllegalStateException("no " + name + " in " + uri);
  }
}
