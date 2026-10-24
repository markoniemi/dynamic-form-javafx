package com.example.desktop.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.json.JsonMapperFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The panes share selection and session through this and nothing else. */
class AppStateTest {

  @Test
  void statusStartsEmptyAndCarriesWhatIsPutInIt() {
    AppState state = new AppState();
    assertEquals("", state.status().get());
    state.status().set("Loaded 3 forms");
    assertEquals("Loaded 3 forms", state.status().get());
  }

  @Test
  void adminFollowsTheSessionItIsGiven() {
    AppState state = new AppState();

    assertFalse(state.admin().get(), "no session means no admin actions");
    state.session().set(session("USER"));
    assertFalse(state.admin().get());
    state.session().set(session("ADMIN", "USER"));
    assertTrue(state.admin().get());
  }

  @Test
  void selectingAFormNotifiesListeners() {
    AppState state = new AppState();
    List<String> seen = new ArrayList<>();
    state.selectedFormKey().addListener((obs, old, now) -> seen.add(now));

    state.selectedFormKey().set("feedback");

    assertEquals(List.of("feedback"), seen);
  }

  /** Builds a session carrying the given bare role names, the way the real token decode would. */
  private static AuthSession session(String... roles) {
    String claims =
        "{\"roles\":["
            + String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList())
            + "]}";
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
            + ".signature";
    return AuthSession.fromToken(token, JsonMapperFactory.create());
  }

  /** Guards the assumption the helper above rests on. */
  @Test
  void theHelperProducesTheRolesItClaims() {
    assertEquals(Set.of("ADMIN", "USER"), session("ADMIN", "USER").roles());
  }
}
