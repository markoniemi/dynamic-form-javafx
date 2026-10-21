package com.example.desktop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Obtains an access token without a browser, by driving the auth server's login form directly.
 *
 * <p>This is a test shortcut, not the production path: the real client opens the system browser
 * (see {@code PkceLoginFlow}). It exists so API tests can get a token for a given user cheaply.
 * Adapted from {@code DynamicFormStackIT} in dynamic-form's testcontainers module, which drives the
 * same server.
 */
final class TokenClient {

  private static final String CLIENT_ID = "desktop-client";
  private static final String REDIRECT_URI = "http://localhost:8123/callback";
  private static final String VERIFIER = "test-verifier-0123456789-0123456789-0123456789";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TokenClient() {}

  /** Runs an authorization-code + PKCE flow as the given user and returns the access token. */
  static String accessToken(String username, String password) {
    try {
      return login(username, password);
    } catch (Exception e) {
      throw new IllegalStateException("Headless login failed for " + username, e);
    }
  }

  private static String login(String username, String password) throws Exception {
    HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    String authorize =
        StackFixture.authUrl()
            + "/oauth2/authorize?response_type=code&scope=openid&code_challenge_method=S256"
            + "&client_id="
            + CLIENT_ID
            + "&redirect_uri="
            + REDIRECT_URI
            + "&code_challenge="
            + challenge();

    URI login = redirect(client, HttpRequest.newBuilder(URI.create(authorize)).build());
    String page =
        client
            .send(HttpRequest.newBuilder(login).build(), HttpResponse.BodyHandlers.ofString())
            .body();
    Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page);
    if (!csrf.find()) {
      throw new IllegalStateException("No _csrf field on the login page");
    }

    URI resume =
        redirect(
            client,
            HttpRequest.newBuilder(login)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    form(
                        Map.of(
                            "username", username, "password", password, "_csrf", csrf.group(1))))
                .build());
    URI callback = redirect(client, HttpRequest.newBuilder(resume).build());
    Matcher code = Pattern.compile("[?&]code=([^&]+)").matcher(callback.toString());
    if (!code.find()) {
      throw new IllegalStateException("No authorization code in " + callback);
    }

    HttpResponse<String> tokens =
        client.send(
            HttpRequest.newBuilder(URI.create(StackFixture.authUrl() + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    form(
                        Map.of(
                            "grant_type", "authorization_code",
                            "code", code.group(1),
                            "redirect_uri", REDIRECT_URI,
                            "client_id", CLIENT_ID,
                            "code_verifier", VERIFIER)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (tokens.statusCode() != 200) {
      throw new IllegalStateException("Token endpoint returned " + tokens.statusCode() + ": " + tokens.body());
    }
    JsonNode json = MAPPER.readTree(tokens.body());
    return json.get("access_token").asText();
  }

  /** Sends the request, expects a redirect, and resolves its target against the request URI. */
  private static URI redirect(HttpClient client, HttpRequest request) throws Exception {
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 302) {
      throw new IllegalStateException(
          request.uri() + " returned " + response.statusCode() + ", expected a redirect");
    }
    return request.uri().resolve(response.headers().firstValue("Location").orElseThrow());
  }

  private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
    return HttpRequest.BodyPublishers.ofString(
        fields.entrySet().stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&")));
  }

  private static String challenge() throws Exception {
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }
}
