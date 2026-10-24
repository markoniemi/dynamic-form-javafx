package com.example.desktop.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.auth.PkceLoginFlow;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        FormService.FORMS,
        200,
        "application/json",
        "[{\"formKey\":\"contact\",\"title\":\"Contact Us\"}]");
    ApiClient client = new ApiClient(baseUrl, () -> "tok-123", JsonMapperFactory.create());

    List<FormListItemDto> forms =
        client.get(FormService.FORMS, new TypeReference<List<FormListItemDto>>() {});

    assertEquals("contact", forms.get(0).getFormKey());
    assertEquals(List.of("Bearer tok-123"), seenAuthHeaders);
  }

  @Test
  void getTextReturnsTheRawBodyForThePlainTextIssuerEndpoint() {
    stub(PkceLoginFlow.ISSUER_URI_PATH, 200, "text/plain", "http://localhost:9000");
    ApiClient client = new ApiClient(baseUrl, () -> null, JsonMapperFactory.create());

    assertEquals("http://localhost:9000", client.getText(PkceLoginFlow.ISSUER_URI_PATH));
    assertTrue(seenAuthHeaders.isEmpty(), "a null token must not produce an Authorization header");
  }

  @Test
  void postAcceptsTheCreatedStatus() {
    stub(FormService.FORMS, 201, "application/json", "{\"formKey\":\"it-form\",\"title\":\"IT\"}");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    assertEquals("it-form", client.post(FormService.FORMS, Map.of(), FormDto.class).getFormKey());
  }

  @Test
  void deleteAcceptsBoth204AndThe200TheFormDataEndpointReturns() {
    stub("/api/forms/gone", 204, null, "");
    stub("/api/form-data/submission/7", 200, null, "");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    assertDoesNotThrow(() -> client.delete(FormService.path("gone")));
    assertDoesNotThrow(() -> client.delete(FormDataService.submissionPath(7L)));
  }

  @Test
  void validationFailureBecomesAnApiExceptionWithFieldErrors() {
    stub(
        FormService.FORMS,
        400,
        "application/problem+json",
        """
        {"status":400,"detail":"Validation failed",
         "errors":[{"field":"title","message":"Title is required","code":"NotBlank"}]}""");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    ApiException e =
        assertThrows(
            ApiException.class, () -> client.post(FormService.FORMS, Map.of(), FormDto.class));

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
        new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create(), Duration.ofMillis(300));

    assertThrows(
        Exception.class,
        () -> client.get(FormService.FORMS, new TypeReference<List<FormListItemDto>>() {}));
  }

  /**
   * Answers 401 to any request carrying {@code Bearer expired} and 200 to anything else, which is
   * what the resource server does the moment a new token is minted.
   */
  private void stubRejectingTheStaleToken() {
    server.createContext(
        FormService.FORMS,
        exchange -> {
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          seenAuthHeaders.add(auth);
          if ("Bearer expired".equals(auth)) {
            exchange.sendResponseHeaders(401, -1);
          } else {
            byte[] body =
                "[{\"formKey\":\"contact\",\"title\":\"Contact Us\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          }
          exchange.close();
        });
  }

  private List<FormListItemDto> listForms(ApiClient client) {
    return client.get(FormService.FORMS, new TypeReference<List<FormListItemDto>>() {});
  }

  @Test
  void anExpiredTokenIsRetriedOnceWithAFreshOne() {
    // Spec 4.2 step 5: the token lives an hour, so a long session will meet this.
    stubRejectingTheStaleToken();
    AtomicReference<String> token = new AtomicReference<>("expired");
    AtomicInteger logins = new AtomicInteger();
    ApiClient client =
        new ApiClient(
            baseUrl,
            token::get,
            JsonMapperFactory.create(),
            () -> {
              logins.incrementAndGet();
              token.set("fresh");
              return "fresh";
            });

    assertEquals("contact", listForms(client).get(0).getFormKey());

    assertEquals(1, logins.get(), "exactly one re-login");
    assertEquals(List.of("Bearer expired", "Bearer fresh"), seenAuthHeaders);
  }

  @Test
  void aReLoginThatStillGets401FailsInsteadOfLoopingForever() {
    stub(FormService.FORMS, 401, null, "");
    AtomicInteger logins = new AtomicInteger();
    ApiClient client =
        new ApiClient(
            baseUrl,
            () -> "expired",
            JsonMapperFactory.create(),
            () -> {
              logins.incrementAndGet();
              return "no-better";
            });

    ApiException e = assertThrows(ApiException.class, () -> listForms(client));

    assertEquals(401, e.getStatus());
    assertEquals(1, logins.get(), "one retry only — a 401 after re-login is the user's answer");
  }

  @Test
  void aFailedReLoginSurfacesTheOriginalUnauthorized() {
    // The browser step can be cancelled or the auth server can be down. The caller must still get
    // the readable 401 rather than whatever the login threw.
    stub(FormService.FORMS, 401, null, "");
    ApiClient client =
        new ApiClient(
            baseUrl,
            () -> "expired",
            JsonMapperFactory.create(),
            () -> {
              throw new IllegalStateException("browser closed");
            });

    assertEquals(401, assertThrows(ApiException.class, () -> listForms(client)).getStatus());
  }

  @Test
  void twoThreadsMeetingA401ReLoginOnceBetweenThem() throws Exception {
    // Re-login opens the system browser. Two of them means two browser windows and two callback
    // servers racing for port 8123, so the second caller has to wait and then reuse the new token.
    stubRejectingTheStaleToken();
    AtomicReference<String> token = new AtomicReference<>("expired");
    AtomicInteger logins = new AtomicInteger();
    ApiClient client =
        new ApiClient(
            baseUrl,
            token::get,
            JsonMapperFactory.create(),
            () -> {
              logins.incrementAndGet();
              try {
                Thread.sleep(300); // a human in a browser is far slower than this
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              token.set("fresh");
              return "fresh";
            });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<List<FormListItemDto>> first = pool.submit(() -> listForms(client));
      Future<List<FormListItemDto>> second = pool.submit(() -> listForms(client));

      assertEquals("contact", first.get(30, TimeUnit.SECONDS).get(0).getFormKey());
      assertEquals("contact", second.get(30, TimeUnit.SECONDS).get(0).getFormKey());
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, logins.get(), "the second caller must reuse the token the first obtained");
  }

  @Test
  void expiredTokenGivesAnUnauthorizedApiExceptionNotAJsonParseError() {
    // The resource server answers 401 with an EMPTY body and a WWW-Authenticate header.
    stub(FormService.FORMS, 401, null, "");
    ApiClient client = new ApiClient(baseUrl, () -> "expired", JsonMapperFactory.create());

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> client.get(FormService.FORMS, new TypeReference<List<FormListItemDto>>() {}));

    assertEquals(401, e.getStatus());
    assertNotNull(e.getDetail());
    assertFalse(e.getDetail().isBlank(), "an empty 401 body must still yield a readable message");
    assertTrue(e.getErrors().isEmpty());
  }
}
