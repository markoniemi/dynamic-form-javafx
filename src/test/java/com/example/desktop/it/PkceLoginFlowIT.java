package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormService;
import com.example.desktop.api.Roles;
import com.example.desktop.auth.AuthSession;
import com.example.desktop.auth.PkceLoginFlow;
import com.example.desktop.json.JsonMapperFactory;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The production login path, end to end against the real authorization server — discovery, the
 * loopback callback and the code exchange all run for real. Only the browser is substituted: the
 * launcher drives the login form over HTTP instead of opening a window, so no human is needed.
 */
class PkceLoginFlowIT {

  @Test
  void logsInAsAdminThroughTheRealCallbackAndSeesTheAdminRole() {
    PkceLoginFlow flow =
        new PkceLoginFlow(
            StackFixture.backendUrl(),
            uri -> submitLoginForm(uri, "admin", "admin"),
            JsonMapperFactory.create());

    AuthSession session = flow.login();

    assertFalse(session.accessToken().isBlank());
    assertTrue(session.roles().contains(Roles.ADMIN), session.roles().toString());
    assertTrue(session.isAdmin());
  }

  @Test
  void aPlainUserHasNoAdminRole() {
    PkceLoginFlow flow =
        new PkceLoginFlow(
            StackFixture.backendUrl(),
            uri -> submitLoginForm(uri, "user", "user"),
            JsonMapperFactory.create());

    AuthSession session = flow.login();

    assertTrue(session.roles().contains(Roles.USER), session.roles().toString());
    assertFalse(session.isAdmin());
  }

  @Test
  void theSessionTokenWorksAgainstTheRealApi() {
    AuthSession session =
        new PkceLoginFlow(
                StackFixture.backendUrl(),
                uri -> submitLoginForm(uri, "admin", "admin"),
                JsonMapperFactory.create())
            .login();
    FormService api =
        new FormService(
            new ApiClient(
                StackFixture.backendUrl(), session::accessToken, JsonMapperFactory.create()));

    assertFalse(api.list().isEmpty());
  }

  /**
   * Stands in for the system browser: follows the authorization request to the login page, submits
   * the credentials, and follows the redirect chain until the client's loopback callback is hit.
   */
  private static void submitLoginForm(URI authorizationUri, String username, String password) {
    try {
      HttpClient client =
          HttpClient.newBuilder()
              .cookieHandler(new CookieManager())
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();

      URI login = redirect(client, HttpRequest.newBuilder(authorizationUri).build());
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
                              "username", username,
                              "password", password,
                              "_csrf", csrf.group(1))))
                  .build());
      // This last redirect targets the client's own callback server, which captures the code.
      URI callback = redirect(client, HttpRequest.newBuilder(resume).build());
      client.send(HttpRequest.newBuilder(callback).build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while driving the login form", e);
    } catch (Exception e) {
      throw new IllegalStateException("Could not drive the login form", e);
    }
  }

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
}
