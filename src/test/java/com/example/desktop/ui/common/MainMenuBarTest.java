package com.example.desktop.ui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.i18n.Messages;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.dock.DockLayoutManager;
import java.util.List;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.junit.jupiter.api.Test;

class MainMenuBarTest extends UiTest {

  @Test
  void theViewMenuIsRebuiltWhenItOpens() {
    RecordingActions actions = new RecordingActions(List.of());
    MenuBar bar = onFx(() -> new MainMenuBar(actions));
    Menu view = fxValue(() -> bar.getMenus().get(1));
    assertEquals(List.of(Messages.get("menu.resetLayout")), fxValue(() -> texts(view)));

    // A pane closed since the bar was built: only a rebuild on show can list it.
    actions.reopenable = List.of(DockLayoutManager.SUBMISSIONS);
    onFx(view::show);

    assertEquals(
        List.of(Messages.get("navigation.submissions"), Messages.get("menu.resetLayout")),
        fxValue(() -> texts(view)));
  }

  @Test
  void signOutAndExitReachTheActions() {
    RecordingActions actions = new RecordingActions(List.of());
    MenuBar bar = onFx(() -> new MainMenuBar(actions));
    List<MenuItem> fileItems = fxValue(() -> bar.getMenus().getFirst().getItems());
    onFx(() -> fileItems.get(0).fire());
    onFx(() -> fileItems.get(1).fire());
    assertTrue(actions.signedOut);
    assertTrue(actions.exited);
  }

  private static List<String> texts(Menu menu) {
    return menu.getItems().stream()
        .filter(item -> !(item instanceof SeparatorMenuItem))
        .map(MenuItem::getText)
        .toList();
  }

  private static final class RecordingActions implements MainMenuBar.Actions {
    List<String> reopenable;
    boolean signedOut;
    boolean exited;

    RecordingActions(List<String> reopenable) {
      this.reopenable = reopenable;
    }

    @Override
    public void reopen(String layoutId) {}

    @Override
    public void resetLayout() {}

    @Override
    public void signOut() {
      signedOut = true;
    }

    @Override
    public void exit() {
      exited = true;
    }

    @Override
    public List<String> reopenable() {
      return reopenable;
    }
  }
}
