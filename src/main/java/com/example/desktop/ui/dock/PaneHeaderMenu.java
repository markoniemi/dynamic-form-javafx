package com.example.desktop.ui.dock;

import com.example.desktop.i18n.Messages;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import org.snapfx.SnapFX;
import org.snapfx.model.DockNode;
import org.snapfx.view.DockNodeView;

/** Replaces SnapFX's float button in a pane header with a three-dots menu. */
final class PaneHeaderMenu {

  /** Marks the menu button, which is how a repeated decoration finds it and skips. */
  public static final String MENU_STYLE_CLASS = "pane-menu";

  private PaneHeaderMenu() {}

  /**
   * Idempotent: safe to call after every relayout, since SnapFX offers no view-created hook. Does
   * nothing when the node's view has not been built yet.
   */
  static void decorate(SnapFX snapFX, DockNode node) {
    DockNodeView view = snapFX.getLayoutEngine().getDockNodeView(node);
    if (view == null) {
      return;
    }
    view.setFloatButtonVisible(false);
    if (view.getHeader().getChildren().stream()
        .anyMatch(child -> child.getStyleClass().contains(MENU_STYLE_CLASS))) {
      return;
    }

    // A glyph rather than a Messages key, as with the refresh button: it needs no translation.
    MenuButton menu = new MenuButton("⋯");
    menu.getStyleClass().add(MENU_STYLE_CLASS);
    menu.setTooltip(new Tooltip(Messages.get("pane.menu")));

    // Straight to SnapFX rather than DockNodeView.setOnFloatRequest, which SnapFX wires itself.
    MenuItem floatItem = new MenuItem(Messages.get("pane.float"));
    floatItem.setOnAction(event -> snapFX.floatNode(node));
    MenuItem closeItem = new MenuItem(Messages.get("pane.close"));
    closeItem.setOnAction(event -> snapFX.close(node));
    menu.getItems().addAll(floatItem, closeItem);

    view.getHeader().getChildren().add(menu);
  }
}
