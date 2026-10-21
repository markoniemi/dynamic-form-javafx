package com.example.desktop.auth;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.ApiPaths;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OAuth2 authorization code flow with PKCE, in the shape RFC 8252 prescribes for a native app:
 * the system browser does the login, and the result comes back to a loopback listener.
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
    String issuer =
        new ApiClient(backendUrl, () -> null, mapper).getText(ApiPaths.CONFIG_ISSUER_URI);
    JsonNode metadata = discover(issuer);
    String authorizationEndpoint = metadata.get("authorization_endpoint").asText();
    String tokenEndpoint = metadata.get("token_endpoint").asText();

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
      return mapper.readTree(response.body()).get("access_token").asText();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read the token response", e);
    }
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
