package com.example.desktop.ui.common;

import com.example.desktop.i18n.Messages;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Covers a pane while a call is in flight, so a request that takes a while looks like work rather
 * than a frozen window. Also blocks input, which keeps a double submit from happening.
 */
public class BusyOverlay extends StackPane {

  public BusyOverlay() {
    getStyleClass().add("busy-overlay");
    VBox content = new VBox(8, new ProgressIndicator(), new Label(Messages.get("common.loading")));
    content.setStyle("-fx-alignment: center;");
    getChildren().add(content);
    setBusy(false);
  }

  public void setBusy(boolean busy) {
    setVisible(busy);
    setManaged(busy);
    // Visible means it swallows clicks; hidden means it must not intercept them.
    setMouseTransparent(!busy);
  }

  public boolean isBusy() {
    return isVisible();
  }
}
