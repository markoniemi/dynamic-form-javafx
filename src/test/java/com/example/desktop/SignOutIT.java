package com.example.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.config.DesktopConfig;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.common.ErrorBanner;
import com.example.desktop.ui.common.LoginPane;
import com.example.desktop.ui.dock.DockLayoutManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No backend: an injected {@link AuthSession}, as spec section 5.3 prescribes. The panes' own
 * silent loads fail against the unreachable default URL and land in their banners, which is
 * harmless here: what is under test is the window, not the API.
 */
class SignOutIT extends UiTest {

  @TempDir Path layoutDir;

  @Test
  void signingOutGoesBackToTheLoginScreen() {
    DesktopApp app = newApp();
    Stage stage = newStage();

    onFx(() -> app.showMainWindow(stage, session("USER")));
    onFx(() -> app.signOut(stage));

    assertInstanceOf(LoginPane.class, fxValue(() -> stage.getScene().getRoot()));
  }

  @Test
  void signingInAsAnAdminAfterAUserRebuildsTheLayoutWithTheEditor() {
    // Whether the editor exists was settled when the window was built, so a new session with
    // other roles used to keep the old panes.
    DesktopApp app = newApp();
    Stage stage = newStage();

    onFx(() -> app.showMainWindow(stage, session("USER")));
    assertFalse(
        fxValue(
            () -> app.layoutManager().layoutIdsInOrder().contains(DockLayoutManager.FORM_EDITOR)));

    onFx(() -> app.signOut(stage));
    onFx(() -> app.showMainWindow(stage, session("USER", "ADMIN")));

    assertTrue(
        fxValue(
            () -> app.layoutManager().layoutIdsInOrder().contains(DockLayoutManager.FORM_EDITOR)));
    assertNotNull(fxValue(() -> app.panes().editor()));
  }

  @Test
  void aRequestThatFinishesAfterASignOutDoesNotWriteIntoTheNewSession() {
    // A load started before the sign-out completes afterwards, on a pane that is no longer on
    // screen, and must not push its status or its form into the new session.
    DesktopApp app = newApp();
    Stage stage = newStage();

    onFx(() -> app.showMainWindow(stage, session("USER")));
    SessionPanes retired = app.panes();

    onFx(() -> app.signOut(stage));
    onFx(() -> app.showMainWindow(stage, session("USER")));
    SessionPanes current = app.panes();

    assertFalse(fxValue(retired::isCurrent));
    assertTrue(fxValue(current::isCurrent));
    onFx(
        () -> {
          retired.runIfCurrent(() -> current.state().status().set("from the old session"));
          // Sanity: the guard is the only thing standing in the way.
          current.runIfCurrent(() -> current.state().status().set("from the new session"));
        });
    assertEquals("from the new session", fxValue(() -> current.state().status().get()));
  }

  @Test
  void aRetiredSessionIsNotWrittenIntoEvenWhenNothingElseHappens() {
    DesktopApp app = newApp();
    Stage stage = newStage();
    onFx(() -> app.showMainWindow(stage, session("USER")));
    SessionPanes retired = app.panes();
    onFx(() -> app.signOut(stage));
    onFx(() -> app.showMainWindow(stage, session("USER")));
    SessionPanes current = app.panes();
    onFx(() -> current.state().status().set(""));
    onFx(() -> retired.runIfCurrent(() -> current.state().status().set("from the old session")));
    assertEquals("", fxValue(() -> current.state().status().get()));
  }

  @Test
  void openingTheMainWindowAsksTheBackendForData() {
    // The loads fail against the unreachable default URL; a failure landing in a pane's banner
    // proves the window attempted them. StartupIT calls startLoad directly, so it cannot notice
    // showMainWindow forgetting to.
    DesktopApp app = newApp();
    Stage stage = newStage();

    onFx(() -> app.showMainWindow(stage, session("USER")));

    waitUntil(() -> bannerText(app.panes().list()) && bannerText(app.panes().submissions()));
  }

  private static boolean bannerText(javafx.scene.Parent pane) {
    return fxValue(
        () ->
            pane.lookupAll(".error-banner").stream()
                .anyMatch(n -> !((ErrorBanner) n).text().isBlank()));
  }

  /** Never the user's own configuration: signing out writes the layout file. */
  private DesktopApp newApp() {
    Properties overrides = new Properties();
    overrides.setProperty("layout.file", layoutDir.resolve("layout.json").toString());
    return new DesktopApp(DesktopConfig.load(layoutDir.resolve("none.properties"), overrides));
  }

  private Stage newStage() {
    return onFx(
        () -> {
          Stage s = new Stage();
          s.setScene(new Scene(new StackPane()));
          return s;
        });
  }

  /** An unsigned JWT with the given bare role names, as ShellIT builds one. */
  private static AuthSession session(String... roles) {
    String claims =
        "{\"roles\":["
            + String.join(",", Arrays.stream(roles).map(r -> "\"" + r + "\"").toList())
            + "]}";
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
            + ".sig";
    return AuthSession.fromToken(token, JsonMapperFactory.create());
  }
}
