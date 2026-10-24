package com.example.desktop.ui.common;

import com.example.desktop.i18n.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/**
 * The screen the window opens on. The sign-in flow opens a browser, so it starts when the user asks
 * for it rather than when the window appears; this pane only decides <em>when</em>.
 */
public class LoginPane extends BorderPane {

  private final Button signIn = new Button(Messages.get("login.signIn"));
  private final Label message = new Label();
  private final BusyOverlay overlay = new BusyOverlay();

  public LoginPane(Runnable onSignIn) {
    Label prompt = new Label(Messages.get("login.prompt"));
    prompt.getStyleClass().add("login-prompt");
    signIn.setDefaultButton(true);
    // The pane owns the one-flow-at-a-time rule: the port the flow listens on is fixed, so a second
    // click must not start a second flow. Whoever supplies onSignIn cannot forget this.
    signIn.setOnAction(
        event -> {
          setBusy(true);
          onSignIn.run();
        });
    message.setWrapText(true);
    message.setMaxWidth(420);
    VBox column = new VBox(12, prompt, signIn, message, overlay);
    column.setAlignment(Pos.CENTER);
    setCenter(column);
  }

  /** While signing in the button is disabled, so a second click cannot start a second flow. */
  public void setBusy(boolean busy) {
    signIn.setDisable(busy);
    overlay.setBusy(busy);
    if (busy) {
      message.setText(Messages.get("login.inProgress"));
    }
  }

  /** Shows why the last attempt failed, or any other note, under the button. */
  public void showMessage(String text) {
    message.setText(text == null ? "" : text);
  }

  public String messageText() {
    return message.getText();
  }

  public Button signInButton() {
    return signIn;
  }
}
