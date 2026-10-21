package com.example.desktop;

import com.example.desktop.auth.AuthSession;
import com.example.desktop.auth.BrowserLauncher;
import com.example.desktop.auth.PkceLoginFlow;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.scene.Scene;
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

    // Blocks until the user finishes in the browser. Acceptable only because nothing is on screen
    // yet; once there is a window, login belongs off the FX thread behind a progress indicator.
    AuthSession session =
        new PkceLoginFlow(
                backendUrl, BrowserLauncher.systemBrowser(), JsonMapperFactory.create())
            .login();

    layout = new DockLayoutManager(new SnapFX(), layoutStore(), session);
    stage.setTitle("Dynamic Form Desktop" + (session.isAdmin() ? " (admin)" : ""));
    stage.setScene(new Scene(layout.build(stage), 1100, 750));
    stage.show();
  }

  @Override
  public void stop() {
    if (layout != null) {
      layout.saveLayout();
    }
  }

  private static LayoutStore layoutStore() {
    return new LayoutStore(
        Path.of(System.getProperty("user.home"), ".dynamic-form-desktop", "layout.json"));
  }
}
