package com.example.desktop.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Receives the authorization server's redirect on {@code http://localhost:<port>/callback}.
 *
 * <p>A desktop client has no web server to redirect to, so RFC 8252 has it listen on the loopback
 * interface for exactly one request. The port is fixed in production because the authorization
 * server only accepts the redirect URI it has registered.
 */
public class LoopbackCallbackServer implements AutoCloseable {

  private static final String CALLBACK_PATH = "/callback";

  private final HttpServer server;
  private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

  /**
   * Starts listening. Port 0 picks any free port, which is only useful in tests — see {@link
   * #port()}.
   *
   * @throws IllegalStateException if the port is already held by another process, naming the port,
   *     because "address already in use" alone does not tell the user what to close
   */
  public LoopbackCallbackServer(int port) {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    } catch (BindException e) {
      throw new IllegalStateException(
          "Cannot listen on port "
              + port
              + " for the login callback: it is already in use. Close whatever holds port "
              + port
              + " (another instance of this application, most likely) and try again.",
          e);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot start the login callback server on port " + port, e);
    }
    server.createContext(CALLBACK_PATH, this::handle);
    server.start();
  }

  /** The port actually bound, which differs from the requested one when 0 was asked for. */
  public int port() {
    return server.getAddress().getPort();
  }

  /** The redirect URI to send in the authorization request, byte-identical to the registered one. */
  public String redirectUri() {
    return "http://localhost:" + port() + CALLBACK_PATH;
  }

  /**
   * Waits for the browser to arrive and returns the authorization code.
   *
   * @throws IllegalStateException if the authorization server reported an error, if {@code state}
   *     does not match, or if no code arrived before the timeout
   */
  public String awaitCode(String expectedState, Duration timeout) {
    Map<String, String> params;
    try {
      params = callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException(
          "No login callback arrived within " + timeout.toSeconds() + "s", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("The login callback failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the login callback", e);
    }

    String error = params.get("error");
    if (error != null) {
      throw new IllegalStateException("Login failed: the authorization server returned " + error);
    }
    if (!expectedState.equals(params.get("state"))) {
      // Either a stale callback or a forged one; either way this response is not ours.
      throw new IllegalStateException("Login failed: the callback carried an unexpected state");
    }
    String code = params.get("code");
    if (code == null) {
      throw new IllegalStateException("Login failed: the callback carried no authorization code");
    }
    return code;
  }

  private void handle(HttpExchange exchange) throws IOException {
    Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
    callback.complete(params);

    // The browser is showing this tab to the user, so say something rather than nothing.
    boolean ok = params.containsKey("code") && !params.containsKey("error");
    byte[] body =
        ("<html><body style=\"font-family:sans-serif\"><h2>"
                + (ok ? "Signed in" : "Sign-in failed")
                + "</h2><p>"
                + (ok
                    ? "You can close this tab and return to the application."
                    : "Return to the application for details.")
                + "</p></body></html>")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(ok ? 200 : 400, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> params = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return params;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        params.put(
            java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return params;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
