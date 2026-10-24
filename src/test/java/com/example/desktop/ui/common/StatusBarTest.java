package com.example.desktop.ui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class StatusBarTest extends UiTest {

  @Test
  void theBarShowsWhateverTheStateSays() {
    AppState state = new AppState();
    StatusBar bar = onFx(() -> new StatusBar(state));

    onFx(() -> state.status().set("Submitted"));

    assertEquals("Submitted", fxValue(bar::text));
  }

  @Test
  void theBarNamesTheSignedInUser() {
    AppState state = stateFor("admin", "\"ROLE_USER\"");
    StatusBar bar = onFx(() -> new StatusBar(state));

    String text = fxValue(bar::userText);

    assertTrue(text.contains("admin"));
    assertFalse(text.contains("(admin)"), "a plain user is not marked as an admin");
  }

  @Test
  void anAdminIsMarkedAsOne() {
    AppState state = stateFor("admin", "\"ROLE_ADMIN\",\"ROLE_USER\"");
    StatusBar bar = onFx(() -> new StatusBar(state));

    assertEquals("admin (admin)", fxValue(bar::userText));
  }

  @Test
  void theUserFollowsTheSession() {
    AppState state = new AppState();
    StatusBar bar = onFx(() -> new StatusBar(state));
    assertEquals("", fxValue(bar::userText));

    onFx(() -> state.session().set(sessionFor("admin", "\"ROLE_USER\"")));

    assertEquals("admin", fxValue(bar::userText));
  }

  private static AppState stateFor(String username, String roles) {
    AppState state = new AppState();
    state.session().set(sessionFor(username, roles));
    return state;
  }

  private static AuthSession sessionFor(String username, String roles) {
    String claims = "{\"preferred_username\":\"" + username + "\",\"roles\":[" + roles + "]}";
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
            + ".sig";
    return AuthSession.fromToken(token, JsonMapperFactory.create());
  }
}
