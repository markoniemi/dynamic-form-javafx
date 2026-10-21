package com.example.desktop.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.json.JsonMapperFactory;
import com.example.dynamicform.api.FormDto;
import com.example.dynamicform.api.FormListItemDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the client against a local {@link HttpServer} on an ephemeral port, so the awkward
 * parts of the backend's contract — a text/plain endpoint, four different success statuses, a
 * problem document, an empty-bodied 401 — are all reproducible without Docker.
 */
class ApiClientTest {

  private HttpServer server;
  private String baseUrl;
  private final List<String> seenAuthHeaders = new ArrayList<>();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  /** Registers a canned response and records the Authorization header each request carried. */
  private void stub(String path, int status, String contentType, String body) {
    server.createContext(
        path,
        exchange -> {
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          if (auth != null) {
            seenAuthHeaders.add(auth);
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
          }
          // a 204 must not carry a body, and -1 is how HttpExchange says "no content"
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          }
          exchange.close();
        });
  }

  @Test
  void getDeserializesAndSendsTheBearerToken() {
    stub(
        ApiPaths.FORMS,
        200,
        "application/json",
        "[{\"formKey\":\"contact\",\"title\":\"Contact Us\"}]");
    ApiClient client = new ApiClient(baseUrl, () -> "tok-123", JsonMapperFactory.create());

    List<FormListItemDto> forms =
        client.get(ApiPaths.FORMS, new TypeReference<List<FormListItemDto>>() {});

    assertEquals("contact", forms.get(0).getFormKey());
    assertEquals(List.of("Bearer tok-123"), seenAuthHeaders);
  }

  @Test
  void getTextReturnsTheRawBodyForThePlainTextIssuerEndpoint() {
    stub(ApiPaths.CONFIG_ISSUER_URI, 200, "text/plain", "http://localhost:9000");
    ApiClient client = new ApiClient(baseUrl, () -> null, JsonMapperFactory.create());

    assertEquals("http://localhost:9000", client.getText(ApiPaths.CONFIG_ISSUER_URI));
    assertTrue(seenAuthHeaders.isEmpty(), "a null token must not produce an Authorization header");
  }

  @Test
  void postAcceptsTheCreatedStatus() {
    stub(ApiPaths.FORMS, 201, "application/json", "{\"formKey\":\"it-form\",\"title\":\"IT\"}");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    assertEquals("it-form", client.post(ApiPaths.FORMS, Map.of(), FormDto.class).getFormKey());
  }

  @Test
  void deleteAcceptsBoth204AndThe200TheFormDataEndpointReturns() {
    stub("/api/forms/gone", 204, null, "");
    stub("/api/form-data/submission/7", 200, null, "");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    assertDoesNotThrow(() -> client.delete(ApiPaths.form("gone")));
    assertDoesNotThrow(() -> client.delete(ApiPaths.submission(7L)));
  }

  @Test
  void validationFailureBecomesAnApiExceptionWithFieldErrors() {
    stub(
        ApiPaths.FORMS,
        400,
        "application/problem+json",
        """
        {"status":400,"detail":"Validation failed",
         "errors":[{"field":"title","message":"Title is required","code":"NotBlank"}]}""");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    ApiException e =
        assertThrows(
            ApiException.class, () -> client.post(ApiPaths.FORMS, Map.of(), FormDto.class));

    assertEquals("title", e.getErrors().get(0).getField());
  }

  @Test
  void aServerThatNeverAnswersFailsInsteadOfBlockingForever() {
    // Accepts the connection and then says nothing, which is how a hung backend behaves. Without
    // a request timeout the calling thread parks indefinitely.
    server.createContext(
        "/api/forms",
        exchange -> {
          try {
            Thread.sleep(1_500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    ApiClient client =
        new ApiClient(
            baseUrl, () -> "t", JsonMapperFactory.create(), Duration.ofMillis(300));

    assertThrows(
        Exception.class,
        () -> client.get(ApiPaths.FORMS, new TypeReference<List<FormListItemDto>>() {}));
  }

  @Test
  void expiredTokenGivesAnUnauthorizedApiExceptionNotAJsonParseError() {
    // The resource server answers 401 with an EMPTY body and a WWW-Authenticate header.
    stub(ApiPaths.FORMS, 401, null, "");
    ApiClient client = new ApiClient(baseUrl, () -> "expired", JsonMapperFactory.create());

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> client.get(ApiPaths.FORMS, new TypeReference<List<FormListItemDto>>() {}));

    assertEquals(401, e.getStatus());
    assertNotNull(e.getDetail());
    assertFalse(e.getDetail().isBlank(), "an empty 401 body must still yield a readable message");
    assertTrue(e.getErrors().isEmpty());
  }
}
