package com.example.desktop.ui.common;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.i18n.Messages;
import com.example.desktop.state.AppState;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The line along the bottom of the window: the last thing that happened on the left, who is signed
 * in on the right. It only reads {@link AppState}; panes publish to {@code status()} and never
 * reference the bar.
 */
public class StatusBar extends HBox {

  private final Label message = new Label();
  private final Label user = new Label();

  public StatusBar(AppState state) {
    getStyleClass().add("status-bar");
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(4, 8, 4, 8));

    message.textProperty().bind(state.status());
    // Built by the bundle rather than concatenated, as the window title is, so a translation can
    // put the marker first.
    StringBinding userText =
        Bindings.createStringBinding(
            () -> {
              AuthSession session = state.session().get();
              if (session == null) {
                return "";
              }
              return state.admin().get()
                  ? Messages.format("app.titleAdmin", session.username())
                  : session.username();
            },
            state.session(),
            state.admin());
    user.textProperty().bind(userText);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    getChildren().addAll(message, spacer, user);
  }

  /** The message currently shown. */
  public String text() {
    return message.getText();
  }

  /** The signed-in user currently shown; empty when nobody is. */
  public String userText() {
    return user.getText();
  }
}
