package com.example.desktop.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The client's single HTTP entry point. Adds the bearer token, serializes bodies and turns any
 * non-2xx response into an {@link ApiException}.
 *
 * <p>No UI class uses this directly, and no call belongs on the JavaFX application thread.
 *
 * <p>The token arrives as a {@link Supplier} rather than a string so a session can be replaced —
 * after a re-login on 401, say — without rebuilding the client. The supplier may return null, which
 * is how the one public endpoint is called before anyone has logged in.
 */
public class ApiClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final String baseUrl;
  private final Supplier<String> tokenSupplier;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final Duration requestTimeout;
  private final Reauthenticator reauthenticator;

  /** Serializes re-login, so concurrent 401s cannot each start a browser flow. */
  private final Object loginLock = new Object();

  public ApiClient(String baseUrl, Supplier<String> tokenSupplier, ObjectMapper mapper) {
    this(baseUrl, tokenSupplier, mapper, null, REQUEST_TIMEOUT);
  }

  /**
   * The constructor the application uses: a rejected token is replaced by {@code reauthenticator}
   * and the request is sent once more. Passing null keeps a 401 final, which is what the tests and
   * any caller without a login flow want.
   */
  public ApiClient(
      String baseUrl,
      Supplier<String> tokenSupplier,
      ObjectMapper mapper,
      Reauthenticator reauthenticator) {
    this(baseUrl, tokenSupplier, mapper, reauthenticator, REQUEST_TIMEOUT);
  }

  /**
   * Lets a caller shorten the per-request timeout. Tests use it to prove a hung server surfaces as
   * a failure rather than parking the thread; production uses the default.
   */
  ApiClient(
      String baseUrl,
      Supplier<String> tokenSupplier,
      ObjectMapper mapper,
      Duration requestTimeout) {
    this(baseUrl, tokenSupplier, mapper, null, requestTimeout);
  }

  private ApiClient(
      String baseUrl,
      Supplier<String> tokenSupplier,
      ObjectMapper mapper,
      Reauthenticator reauthenticator,
      Duration requestTimeout) {
    this.baseUrl = baseUrl;
    this.tokenSupplier = tokenSupplier;
    this.mapper = mapper;
    this.reauthenticator = reauthenticator;
    this.requestTimeout = requestTimeout;
    // Without these a backend that drops SYNs, or accepts the connection and never answers, parks
    // the calling thread for good.
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  /**
   * Reads a {@code text/plain} response verbatim. {@code /api/config/oauth2-issuer-uri} returns a
   * bare string rather than JSON, so parsing it as JSON would fail on the unquoted value.
   */
  public String getText(String path) {
    return send(() -> request(path, "text/plain").GET().build()).strip();
  }

  public <T> T get(String path, Class<T> type) {
    return read(send(() -> jsonRequest(path).GET().build()), type);
  }

  public <T> T get(String path, TypeReference<T> type) {
    return read(send(() -> jsonRequest(path).GET().build()), type);
  }

  public <T> T post(String path, Object body, Class<T> type) {
    return read(send(() -> jsonRequest(path).POST(publish(body)).build()), type);
  }

  public <T> T put(String path, Object body, Class<T> type) {
    return read(send(() -> jsonRequest(path).PUT(publish(body)).build()), type);
  }

  /**
   * Deletes a resource. Accepts any 2xx: the backend answers 204 for a form but 200 for a
   * submission, so neither can be assumed.
   */
  public void delete(String path) {
    send(() -> jsonRequest(path).DELETE().build());
  }

  private HttpRequest.Builder jsonRequest(String path) {
    return request(path, "application/json").header("Content-Type", "application/json");
  }

  private HttpRequest.Builder request(String path, String accept) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(requestTimeout)
            .header("Accept", accept);
    String token = tokenSupplier.get();
    if (token != null && !token.isBlank()) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private HttpRequest.BodyPublisher publish(Object body) {
    return HttpRequest.BodyPublishers.ofString(write(body));
  }

  /**
   * Sends the request and returns the body, or throws {@link ApiException} for any non-2xx.
   *
   * <p>A 401 is retried once against a freshly obtained token, because the access token lives an
   * hour and nothing else in the client notices it expiring (spec 4.2 step 5). The request is built
   * by a supplier rather than passed in so the retry can rebuild it and pick up the new bearer
   * header — {@link HttpRequest} is immutable. Exactly one retry: a 401 that survives a successful
   * re-login is the server's answer, not a stale token.
   */
  private String send(Supplier<HttpRequest> request) {
    String tokenSent = tokenSupplier.get();
    HttpResponse<String> response = exchange(request.get());
    if (response.statusCode() == 401 && reauthenticator != null && reauthenticated(tokenSent)) {
      response = exchange(request.get());
    }
    if (response.statusCode() / 100 != 2) {
      throw ApiException.from(response.statusCode(), response.body(), mapper);
    }
    return response.body();
  }

  /**
   * Replaces the rejected token, at most one login at a time.
   *
   * <p>Panes load in parallel, so several calls can meet the same expired token at once. Logging in
   * opens the system browser and binds the fixed callback port 8123, so a second simultaneous login
   * would show the user a second window and lose the race for the port. Whoever holds the lock logs
   * in; everyone behind it finds the token already changed and simply retries with it.
   *
   * @return true if a token other than the rejected one is now available
   */
  private boolean reauthenticated(String rejected) {
    synchronized (loginLock) {
      String current = tokenSupplier.get();
      if (current != null && !current.isBlank() && !current.equals(rejected)) {
        return true;
      }
      try {
        String fresh = reauthenticator.reauthenticate();
        return fresh != null && !fresh.isBlank() && !fresh.equals(rejected);
      } catch (RuntimeException e) {
        // A cancelled browser step or an unreachable auth server. The caller gets the original 401,
        // which says the session has expired — more use than the login's own failure.
        return false;
      }
    }
  }

  private HttpResponse<String> exchange(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot reach " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling " + request.uri(), e);
    }
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Cannot read a " + type.getSimpleName() + " from the response", e);
    }
  }

  private <T> T read(String body, TypeReference<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read a " + type.getType() + " from the response", e);
    }
  }

  private String write(Object body) {
    try {
      return mapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot serialize the request body", e);
    }
  }

  /**
   * Encodes one path segment.
   *
   * <p>A form key used to be safe by construction, but {@code FormEditorPane} lets an admin type
   * one, and an unencoded key either breaks the request or changes its meaning: a space throws
   * {@link IllegalArgumentException} out of {@code URI.create}, a {@code #} or {@code ?} truncates
   * the path into a fragment or a query, and a {@code /} silently addresses a different endpoint.
   *
   * <p>{@link URLEncoder} is written for {@code application/x-www-form-urlencoded}, where a space
   * becomes {@code +}; in a path it has to be {@code %20}. Rewriting {@code +} afterwards is safe
   * because a literal plus in the input has already become {@code %2B}, so the only {@code +} left
   * is one the encoder produced for a space.
   */
  public static String pathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
