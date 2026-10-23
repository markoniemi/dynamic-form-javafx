package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The four shell behaviours the foundation shipped on reasoning alone. A failure here means the
 * production code is wrong, not the test — that is the whole point of this class.
 *
 * <p>No backend: the panes are stand-in labels, because what is under test is the docking shell.
 */
class ShellIT extends UiTest {

  @TempDir Path layoutDir;

  @Test
  void aLayoutSurvivesSaveAndReload() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("layout.json"));

    // The saved layout must differ from the defaults, or the assertion passes either way: a
    // restore that silently failed would fall back to dockDefaults() and produce the same shape.
    String rearranged =
        onFx(
            () -> {
              DockLayoutManager manager = manager(store, adminState());
              manager.build(freshStage());
              manager.close(manager.nodeById(DockLayoutManager.SUBMISSIONS));
              return manager.currentLayout();
            });
    String defaults =
        onFx(
            () -> {
              DockLayoutManager manager = manager(new LayoutStore(layoutDir.resolve("x.json")), adminState());
              manager.build(freshStage());
              return manager.currentLayout();
            });
    assertNotEquals(
        structureOf(defaults), structureOf(rearranged), "the fixture must not be the default layout");

    store.save(rearranged);
    String reloaded =
        onFx(
            () -> {
              DockLayoutManager manager = manager(store, adminState());
              manager.build(freshStage());
              return manager.currentLayout();
            });

    assertEquals(structureOf(rearranged), structureOf(reloaded));
  }

  /**
   * The layout with its volatile parts removed.
   *
   * <p>SnapFX gives every container and every dock node a fresh random UUID, so two serializations
   * of the same arrangement never match byte for byte. What persistence has to preserve is the
   * structure and the stable layout ids, which is what this keeps.
   */
  private static String structureOf(String layout) {
    return layout
        .replaceAll(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "uuid")
        .replaceAll("\"layoutIdCounter\"\\s*:\\s*\\d+", "\"layoutIdCounter\": n");
  }

  @Test
  void theEditorPaneIsAbsentForANonAdmin() {
    DockLayoutManager manager =
        onFx(() -> manager(new LayoutStore(layoutDir.resolve("user.json")), userState()));

    onFx(() -> manager.build(freshStage()));

    assertNull(fxValue(() -> manager.nodeById(DockLayoutManager.FORM_EDITOR)));
    assertNotNull(fxValue(() -> manager.nodeById(DockLayoutManager.FORM_LIST)));
  }

  @Test
  void anAdminsSavedLayoutLoadedByANonAdminHasTheEditorStripped() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("shared.json"));
    onFx(
        () -> {
          DockLayoutManager asAdmin = manager(store, adminState());
          asAdmin.build(freshStage());
          asAdmin.saveLayout();
        });

    DockLayoutManager asUser = onFx(() -> manager(store, userState()));
    onFx(() -> asUser.build(freshStage()));

    assertNull(fxValue(() -> asUser.nodeById(DockLayoutManager.FORM_EDITOR)));
    assertNotNull(fxValue(() -> asUser.nodeById(DockLayoutManager.FORM_LIST)));
  }

  @Test
  void closingEveryPaneDoesNotLeaveTheNextLaunchBlank() {
    // The C1 regression the foundation review found: SnapFX serializes an empty graph as "{}" and
    // its loadLayout accepts that, so the app used to save a blank layout and start blank forever.
    LayoutStore store = new LayoutStore(layoutDir.resolve("empty.json"));
    onFx(
        () -> {
          DockLayoutManager first = manager(store, adminState());
          first.build(freshStage());
          first.closeAllPanes();
          first.saveLayout();
        });

    DockLayoutManager second = onFx(() -> manager(store, adminState()));
    onFx(() -> second.build(freshStage()));

    assertNotNull(
        fxValue(() -> second.nodeById(DockLayoutManager.FORM_LIST)),
        "a blank saved layout must fall back to the defaults");
  }

  @Test
  void aRestoredLayoutKeepsItsPaneContent() {
    // The other tests here assert that a restored node exists, never that anything is inside it.
    // A pane restored without its content is exactly what an empty panel on screen looks like.
    LayoutStore store = new LayoutStore(layoutDir.resolve("content.json"));
    onFx(
        () -> {
          DockLayoutManager first = manager(store, adminState());
          first.build(freshStage());
          first.saveLayout();
        });

    DockLayoutManager second = onFx(() -> manager(store, adminState()));
    onFx(() -> second.build(freshStage()));

    Node content = fxValue(() -> second.nodeById(DockLayoutManager.FORM_LIST).getContent());
    assertNotNull(content, "a restored pane with no content renders as an empty panel");
    assertEquals(DockLayoutManager.FORM_LIST, ((Label) content).getText());
  }

  /** Each manager needs its own stage: SnapFX.initialize binds to the one it is given. */
  private static Stage freshStage() {
    return new Stage();
  }

  /**
   * Mirrors how App builds a factory: the editor is declined for a non-admin, which is the single
   * place that decision is made.
   */
  private static DockLayoutManager manager(LayoutStore store, AppState state) {
    return new DockLayoutManager(
        new org.snapfx.SnapFX(),
        store,
        nodeId ->
            switch (nodeId) {
              case DockLayoutManager.FORM_LIST,
                      DockLayoutManager.FORM_FILL,
                      DockLayoutManager.SUBMISSIONS ->
                  new Label(nodeId);
              case DockLayoutManager.FORM_EDITOR ->
                  state.admin().get() ? new Label(nodeId) : null;
              // App's factory ends in `default -> null`. Returning a pane for any id at all hides
              // the case that matters: on a restore SnapFX asks by dockNodeId, not by layout id.
              default -> null;
            });
  }

  private static AppState adminState() {
    return stateWithRoles("ADMIN", "USER");
  }

  private static AppState userState() {
    return stateWithRoles("USER");
  }

  private static AppState stateWithRoles(String... roles) {
    AppState state = new AppState();
    String claims =
        "{\"roles\":["
            + String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList())
            + "]}";
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
            + ".sig";
    state.session().set(AuthSession.fromToken(token, JsonMapperFactory.create()));
    return state;
  }
}
