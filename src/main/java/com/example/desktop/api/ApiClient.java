package com.example.desktop.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

  private final String baseUrl;
  private final Supplier<String> tokenSupplier;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public ApiClient(String baseUrl, Supplier<String> tokenSupplier, ObjectMapper mapper) {
    this.baseUrl = baseUrl;
    this.tokenSupplier = tokenSupplier;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().build();
  }

  /**
   * Reads a {@code text/plain} response verbatim. {@code /api/config/oauth2-issuer-uri} returns a
   * bare string rather than JSON, so parsing it as JSON would fail on the unquoted value.
   */
  public String getText(String path) {
    return send(request(path, "text/plain").GET().build()).strip();
  }

  public <T> T get(String path, Class<T> type) {
    return read(send(jsonRequest(path).GET().build()), type);
  }

  public <T> T get(String path, TypeReference<T> type) {
    return read(send(jsonRequest(path).GET().build()), type);
  }

  public <T> T post(String path, Object body, Class<T> type) {
    return read(send(jsonRequest(path).POST(publish(body)).build()), type);
  }

  public <T> T put(String path, Object body, Class<T> type) {
    return read(send(jsonRequest(path).PUT(publish(body)).build()), type);
  }

  /**
   * Deletes a resource. Accepts any 2xx: the backend answers 204 for a form but 200 for a
   * submission, so neither can be assumed.
   */
  public void delete(String path) {
    send(jsonRequest(path).DELETE().build());
  }

  private HttpRequest.Builder jsonRequest(String path) {
    return request(path, "application/json").header("Content-Type", "application/json");
  }

  private HttpRequest.Builder request(String path, String accept) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", accept);
    String token = tokenSupplier.get();
    if (token != null && !token.isBlank()) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private HttpRequest.BodyPublisher publish(Object body) {
    return HttpRequest.BodyPublishers.ofString(write(body));
  }

  /** Sends the request and returns the body, or throws {@link ApiException} for any non-2xx. */
  private String send(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot reach " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling " + request.uri(), e);
    }
    if (response.statusCode() / 100 != 2) {
      throw ApiException.from(response.statusCode(), response.body(), mapper);
    }
    return response.body();
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read a " + type.getSimpleName() + " from the response", e);
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
}
