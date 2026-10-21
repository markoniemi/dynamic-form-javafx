package com.example.desktop;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.auth.BrowserLauncher;
import com.example.desktop.auth.PkceLoginFlow;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.snapfx.SnapFX;

/**
 * Application entry point.
 *
 * <p>The backend URL comes from the {@code backend.url} system property, so the same build runs
 * against a local server or a Testcontainers stack without rebuilding. The layout is stored per
 * user under the home directory.
 */
public class App extends Application {

  private static final String DEFAULT_BACKEND_URL = "http://localhost:8080";

  private DockLayoutManager layout;

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void start(Stage stage) {
    String backendUrl = System.getProperty("backend.url", DEFAULT_BACKEND_URL);

    // The window goes up first, showing a status line. Logging in means three HTTP calls and then
    // waiting for a human in a browser, so it cannot run on the FX thread — and if it fails, the
    // user has to be told. Throwing out of start() only produces a stack trace on stderr, which in
    // a jpackage build nobody ever sees.
    stage.setTitle("Dynamic Form Desktop");
    stage.setScene(new Scene(status("Signing in — complete the login in your browser…"), 1100, 750));
    stage.show();

    Task<AuthSession> login =
        new Task<>() {
          @Override
          protected AuthSession call() {
            return new PkceLoginFlow(
                    backendUrl, BrowserLauncher.systemBrowser(), JsonMapperFactory.create())
                .login();
          }
        };
    login.setOnSucceeded(event -> showMainWindow(stage, login.getValue()));
    login.setOnFailed(event -> reportFatal(login.getException()));

    Thread thread = new Thread(login, "login");
    thread.setDaemon(true);
    thread.start();
  }

  private void showMainWindow(Stage stage, AuthSession session) {
    layout = new DockLayoutManager(new SnapFX(), layoutStore(), session);
    stage.setTitle("Dynamic Form Desktop" + (session.isAdmin() ? " (admin)" : ""));
    stage.getScene().setRoot(layout.build(stage));
  }

  /** Shows why the application cannot continue, then exits. */
  private void reportFatal(Throwable cause) {
    String message = cause == null ? "Unknown error" : cause.getMessage();
    Alert alert =
        new Alert(
            Alert.AlertType.ERROR,
            message == null || message.isBlank() ? cause.toString() : message);
    alert.setHeaderText("Cannot sign in");
    alert.showAndWait();
    Platform.exit();
  }

  @Override
  public void stop() {
    if (layout == null) {
      return;
    }
    try {
      layout.saveLayout();
    } catch (RuntimeException e) {
      // Losing the layout is not worth a crash trace on the way out.
      System.err.println("Could not save the window layout: " + e.getMessage());
    }
  }

  private static StackPane status(String text) {
    Label label = new Label(text);
    label.setAlignment(Pos.CENTER);
    return new StackPane(label);
  }

  private static LayoutStore layoutStore() {
    return new LayoutStore(
        Path.of(System.getProperty("user.home"), ".dynamic-form-desktop", "layout.json"));
  }
}
