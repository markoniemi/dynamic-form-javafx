package com.example.desktop.ui.common;

import com.example.desktop.i18n.Messages;
import com.example.desktop.ui.dock.DockLayoutManager;
import java.util.List;
import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;

/**
 * File, View and Help. The View menu is what makes a closed pane reachable again, so its items are
 * rebuilt each time it opens: a menu built once at startup would not list a pane closed since.
 */
public class MainMenuBar extends MenuBar {

  /** What the menus do. Kept apart from the bar so the bar knows nothing about docking. */
  public interface Actions {
    void reopen(String layoutId);

    void resetLayout();

    void signOut();

    void exit();

    /** Layout ids of the panes that are closed and may be opened again. */
    List<String> reopenable();
  }

  public MainMenuBar(Actions actions) {
    Menu file = new Menu(Messages.get("menu.file"));
    file.getItems()
        .addAll(item("menu.signOut", actions::signOut), item("menu.exit", actions::exit));

    Menu view = new Menu(Messages.get("menu.view"));
    view.setOnShowing(event -> rebuildView(view, actions));
    rebuildView(view, actions); // so the menu is correct before it is ever shown

    Menu help = new Menu(Messages.get("menu.help"));
    help.getItems().add(item("menu.about", MainMenuBar::showAbout));

    getMenus().addAll(file, view, help);
  }

  private static void rebuildView(Menu view, Actions actions) {
    view.getItems().clear();
    for (String id : actions.reopenable()) {
      MenuItem reopen = new MenuItem(DockLayoutManager.titleOf(id));
      reopen.setOnAction(event -> actions.reopen(id));
      view.getItems().add(reopen);
    }
    view.getItems().add(item("menu.resetLayout", actions::resetLayout));
  }

  private static MenuItem item(String key, Runnable action) {
    MenuItem item = new MenuItem(Messages.get(key));
    item.setOnAction(event -> action.run());
    return item;
  }

  private static void showAbout() {
    String version = MainMenuBar.class.getPackage().getImplementationVersion();
    Alert alert =
        new Alert(
            Alert.AlertType.INFORMATION,
            version == null
                ? Messages.get("app.title")
                : Messages.get("app.title") + " " + version);
    alert.setHeaderText(Messages.get("menu.about"));
    alert.show();
  }
}
