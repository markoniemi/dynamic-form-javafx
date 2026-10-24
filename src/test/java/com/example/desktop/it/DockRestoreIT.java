package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import java.nio.file.Path;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What restoring a saved layout does about the one pane whose existence depends on the session. */
class DockRestoreIT extends UiTest {

  @TempDir Path layoutDir;

  @Test
  void anAdminOpeningALayoutSavedByAUserGetsTheEditor() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("user-then-admin.json"));
    DockLayoutManager user = onFx(() -> manager(store, false));
    onFx(() -> user.build(new Stage()));
    assertFalse(fxValue(() -> user.layoutIdsInOrder().contains(DockLayoutManager.FORM_EDITOR)));
    onFx(user::saveLayout);

    DockLayoutManager admin = onFx(() -> manager(store, true));
    onFx(() -> admin.build(new Stage()));

    assertTrue(fxValue(() -> admin.layoutIdsInOrder().contains(DockLayoutManager.FORM_EDITOR)));
  }

  @Test
  void anAdminOpeningALayoutThatHasTheEditorGetsExactlyOne() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("admin-then-admin.json"));
    DockLayoutManager first = onFx(() -> manager(store, true));
    onFx(() -> first.build(new Stage()));
    onFx(first::saveLayout);

    DockLayoutManager second = onFx(() -> manager(store, true));
    onFx(() -> second.build(new Stage()));

    assertEquals(
        1,
        fxValue(
            () ->
                second.layoutIdsInOrder().stream()
                    .filter(DockLayoutManager.FORM_EDITOR::equals)
                    .count()));
  }

  private static DockLayoutManager manager(LayoutStore store, boolean admin) {
    return DockLayoutManager.create(
        store,
        nodeId ->
            switch (nodeId) {
              case DockLayoutManager.FORM_LIST,
                  DockLayoutManager.FORM_FILL,
                  DockLayoutManager.SUBMISSIONS ->
                  new Label(nodeId);
              case DockLayoutManager.FORM_EDITOR -> admin ? new Label(nodeId) : null;
              default -> null;
            });
  }
}
