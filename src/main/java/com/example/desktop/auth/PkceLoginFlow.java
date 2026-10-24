package com.example.desktop.auth;

import com.example.desktop.api.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OAuth2 authorization code flow with PKCE, in the shape RFC 8252 prescribes for a native app: the
 * system browser does the login, and the result comes back to a loopback listener.
 *
 * <p>The client needs only the backend's URL. It asks the backend which issuer to trust, then
 * discovers that issuer's endpoints, so no auth-server URL is compiled in.
 */
public class PkceLoginFlow {

  /**
   * The callback port is fixed, not chosen per attempt, because the authorization server only
   * accepts a redirect URI it has registered — {@code http://localhost:8123/callback}.
   */
  public static final int CALLBACK_PORT = 8123;

  /** Public. Returns a bare {@code text/plain} string, not JSON. */
  public static final String ISSUER_URI_PATH = "/api/config/oauth2-issuer-uri";

  private static final String CLIENT_ID = "desktop-client";
  private static final String SCOPE = "openid profile email";
  private static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(5);

  private final String backendUrl;
  private final BrowserLauncher browser;
  private final ObjectMapper mapper;
  private final HttpClient http = HttpClient.newBuilder().build();

  public PkceLoginFlow(String backendUrl, BrowserLauncher browser, ObjectMapper mapper) {
    this.backendUrl = backendUrl;
    this.browser = browser;
    this.mapper = mapper;
  }

  /**
   * Logs a user in, blocking until the browser comes back.
   *
   * <p>Never call this on the JavaFX application thread: it waits minutes for a human.
   *
   * @throws IllegalStateException if discovery fails, the user cancels, the callback port is busy,
   *     or the code exchange is rejected
   */
  public AuthSession login() {
    String issuer = new ApiClient(backendUrl, () -> null, mapper).getText(ISSUER_URI_PATH);
    JsonNode metadata = discover(issuer);
    String authorizationEndpoint =
        required(metadata, "authorization_endpoint", "the OIDC metadata at " + issuer);
    String tokenEndpoint = required(metadata, "token_endpoint", "the OIDC metadata at " + issuer);

    PkceCodes codes = PkceCodes.generate();
    try (LoopbackCallbackServer callback = new LoopbackCallbackServer(CALLBACK_PORT)) {
      String redirectUri = callback.redirectUri();
      browser.open(URI.create(authorizeUrl(authorizationEndpoint, codes, redirectUri)));
      String code = callback.awaitCode(codes.state(), CALLBACK_TIMEOUT);
      return AuthSession.fromToken(exchange(tokenEndpoint, code, codes, redirectUri), mapper);
    }
  }

  private JsonNode discover(String issuer) {
    String url = issuer + "/.well-known/openid-configuration";
    HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url)).GET().build());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "OIDC discovery at " + url + " returned " + response.statusCode());
    }
    try {
      return mapper.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read the OIDC metadata from " + url, e);
    }
  }

  private String authorizeUrl(String endpoint, PkceCodes codes, String redirectUri) {
    return endpoint
        + "?response_type=code"
        + "&client_id="
        + encode(CLIENT_ID)
        + "&redirect_uri="
        + encode(redirectUri)
        + "&scope="
        + encode(SCOPE)
        + "&state="
        + encode(codes.state())
        + "&code_challenge_method=S256"
        + "&code_challenge="
        + encode(codes.challenge());
  }

  private String exchange(String endpoint, String code, PkceCodes codes, String redirectUri) {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    form(
                        Map.of(
                            "grant_type", "authorization_code",
                            "code", code,
                            // must be byte-identical to the authorization request's value
                            "redirect_uri", redirectUri,
                            "client_id", CLIENT_ID,
                            "code_verifier", codes.verifier())))
                .build());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "The token request was rejected with " + response.statusCode() + ": " + response.body());
    }
    try {
      return required(mapper.readTree(response.body()), "access_token", "the token response");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read the token response", e);
    }
  }

  /**
   * Reads a field that the flow cannot continue without.
   *
   * <p>A wrong issuer URL, a trimmed discovery document, or an authorization server answering an
   * error with status 200 all used to surface as a bare {@link NullPointerException} from an
   * unguarded {@code get(...).asText()} — a stack trace naming nothing the user could act on.
   */
  private static String required(JsonNode document, String field, String source) {
    JsonNode value = document.path(field);
    if (value.isMissingNode() || value.asText().isBlank()) {
      // The document itself is deliberately left out. DesktopApp puts this message straight into a
      // modal
      // alert, where a 4 KB discovery document is unreadable — and a token response missing
      // access_token may still carry an id_token, which must never reach a message or a log. The
      // field names tell the user which document to look at; the names it did carry narrow it down
      // without quoting any values.
      throw new IllegalStateException(
          field + " is missing from " + source + "; it carried " + fieldNames(document));
    }
    return value.asText();
  }

  /** The document's top-level field names, without any of their values. */
  private static String fieldNames(JsonNode document) {
    List<String> names = new ArrayList<>();
    document.fieldNames().forEachRemaining(names::add);
    return names.isEmpty() ? "no fields" : String.join(", ", names);
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot reach " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling " + request.uri(), e);
    }
  }

  private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
    return HttpRequest.BodyPublishers.ofString(
        fields.entrySet().stream()
            .map(e -> e.getKey() + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&")));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
