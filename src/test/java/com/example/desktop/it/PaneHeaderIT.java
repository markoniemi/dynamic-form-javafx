package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.i18n.Messages;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three-dots menu that replaces SnapFX's own float button in each pane header. */
class PaneHeaderIT extends UiTest {

  @TempDir Path layoutDir;

  @Test
  void everyPaneHeaderCarriesTheMenuAndNoFloatButton() {
    DockLayoutManager manager = built();

    assertEquals(1, fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_LIST)).size());
    assertFalse(fxValue(() -> manager.floatButtonVisible(DockLayoutManager.FORM_LIST)));
  }

  @Test
  void theMenuOffersFloatAndClose() {
    DockLayoutManager manager = built();

    MenuButton menu = fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_FILL)).getFirst();
    assertEquals(
        List.of(Messages.get("pane.float"), Messages.get("pane.close")),
        fxValue(() -> menu.getItems().stream().map(MenuItem::getText).toList()));
  }

  @Test
  void closingFromTheMenuRemovesThePaneFromTheGraph() {
    DockLayoutManager manager = built();

    onFx(() -> menuItem(manager, DockLayoutManager.SUBMISSIONS, "pane.close").fire());

    assertFalse(fxValue(() -> manager.layoutIdsInOrder().contains(DockLayoutManager.SUBMISSIONS)));
  }

  @Test
  void theRemainingPanesKeepOneMenuAndNoFloatButtonAfterAStructuralChange() {
    // SnapFX rebuilds every node view on a relayout, so the decoration has to be re-applied by a
    // listener; this is the way a user actually meets the feature.
    DockLayoutManager manager = built();

    onFx(() -> menuItem(manager, DockLayoutManager.SUBMISSIONS, "pane.close").fire());
    onFx(() -> {});
    onFx(() -> {});

    for (String id : List.of(DockLayoutManager.FORM_LIST, DockLayoutManager.FORM_FILL)) {
      assertEquals(1, fxValue(() -> manager.headerMenus(id)).size(), id);
      assertFalse(fxValue(() -> manager.floatButtonVisible(id)), id);
    }
  }

  @Test
  void theTabbedEditorPaneIsDecoratedToo() {
    DockLayoutManager manager = built();

    int menus = fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_EDITOR)).size();
    assertEquals(1, menus);
  }

  @Test
  void aPaneClosedFromTheMenuIsHiddenAndReopensWithItsContent() {
    DockLayoutManager manager = built();

    onFx(() -> menuItem(manager, DockLayoutManager.SUBMISSIONS, "pane.close").fire());

    assertFalse(fxValue(() -> manager.layoutIdsInOrder().contains(DockLayoutManager.SUBMISSIONS)));
    assertEquals(List.of(DockLayoutManager.SUBMISSIONS), fxValue(manager::hiddenLayoutIds));
    assertTrue(fxValue(() -> manager.reopen(DockLayoutManager.SUBMISSIONS)));
    onFx(() -> {});
    onFx(() -> {});
    assertTrue(fxValue(() -> manager.layoutIdsInOrder().contains(DockLayoutManager.SUBMISSIONS)));
    assertNotNull(
        fxValue(() -> manager.nodeById(DockLayoutManager.SUBMISSIONS).getContent()),
        "a reopened pane with no content renders as an empty panel");
    assertEquals(List.of(), fxValue(manager::hiddenLayoutIds));
    assertEquals(
        1,
        fxValue(() -> manager.headerMenus(DockLayoutManager.SUBMISSIONS)).size(),
        "reopen relies on the revision listener to decorate the header");
  }

  @Test
  void aPaneClosedFromTheMenuIsNotCarriedAsHiddenAcrossARestart() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("hidden-restart.json"));
    DockLayoutManager first = onFx(() -> manager(store));
    onFx(() -> first.build(new Stage()));
    onFx(() -> menuItem(first, DockLayoutManager.SUBMISSIONS, "pane.close").fire());
    onFx(first::saveLayout);

    DockLayoutManager second = onFx(() -> manager(store));
    onFx(() -> second.build(new Stage()));

    assertFalse(fxValue(() -> second.layoutIdsInOrder().contains(DockLayoutManager.SUBMISSIONS)));
    assertEquals(List.of(), fxValue(second::hiddenLayoutIds), "hidden nodes are not saved");
    assertTrue(fxValue(() -> second.reopen(DockLayoutManager.SUBMISSIONS)));
    assertNotNull(fxValue(() -> second.nodeById(DockLayoutManager.SUBMISSIONS).getContent()));
  }

  private static MenuItem menuItem(DockLayoutManager manager, String layoutId, String key) {
    return manager.headerMenus(layoutId).getFirst().getItems().stream()
        .filter(item -> Messages.get(key).equals(item.getText()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void decoratingTwiceDoesNotAddASecondMenu() {
    // SnapFX rebuilds and re-caches its node views on any relayout, and there is no view-created
    // hook, so decorateHeaders is called again after every structural change.
    DockLayoutManager manager = built();
    onFx(manager::decorateHeaders);

    assertEquals(1, fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_LIST)).size());
  }

  private DockLayoutManager built() {
    DockLayoutManager manager = onFx(() -> manager(new LayoutStore(layoutDir.resolve("l.json"))));
    onFx(() -> manager.build(new Stage()));
    return manager;
  }

  private static DockLayoutManager manager(LayoutStore store) {
    AppState state = adminState();
    return new DockLayoutManager(
        new org.snapfx.SnapFX(),
        store,
        nodeId ->
            switch (nodeId) {
              case DockLayoutManager.FORM_LIST,
                  DockLayoutManager.FORM_FILL,
                  DockLayoutManager.SUBMISSIONS ->
                  new Label(nodeId);
              case DockLayoutManager.FORM_EDITOR -> state.admin().get() ? new Label(nodeId) : null;
              default -> null;
            });
  }

  private static AppState adminState() {
    AppState state = new AppState();
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String token =
        encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(
                "{\"roles\":[\"ADMIN\",\"USER\"]}".getBytes(StandardCharsets.UTF_8))
            + ".sig";
    state.session().set(AuthSession.fromToken(token, JsonMapperFactory.create()));
    return state;
  }
}
