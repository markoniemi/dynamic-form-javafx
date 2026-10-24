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
import org.junit.jupiter.api.Test;

class MainMenuBarTest extends UiTest {

  @Test
  void theViewMenuListsOnlyWhatIsReopenable() {
    RecordingActions actions = new RecordingActions(List.of(DockLayoutManager.SUBMISSIONS));
    MenuBar bar = onFx(() -> new MainMenuBar(actions));
    Menu view = fxValue(() -> bar.getMenus().get(1));
    onFx(() -> view.show()); // the list is built on show, so a pane closed meanwhile appears
    assertEquals(
        List.of(Messages.get("navigation.submissions"), Messages.get("menu.resetLayout")),
        fxValue(() -> view.getItems().stream().map(MenuItem::getText).toList()));
  }

  @Test
  void signOutAndExitReachTheActions() {
    RecordingActions actions = new RecordingActions(List.of());
    MenuBar bar = onFx(() -> new MainMenuBar(actions));
    onFx(() -> bar.getMenus().getFirst().getItems().getFirst().fire());
    assertTrue(actions.signedOut());
  }

  private static final class RecordingActions implements MainMenuBar.Actions {
    private final List<String> reopenable;
    private boolean signedOut;

    RecordingActions(List<String> reopenable) {
      this.reopenable = reopenable;
    }

    boolean signedOut() {
      return signedOut;
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
    public void exit() {}

    @Override
    public List<String> reopenable() {
      return reopenable;
    }
  }
}
