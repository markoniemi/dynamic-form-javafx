package com.example.desktop;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormApi;
import com.example.desktop.api.FormDataApi;
import com.example.desktop.auth.AuthSession;
import com.example.desktop.auth.BrowserLauncher;
import com.example.desktop.auth.PkceLoginFlow;
import com.example.desktop.i18n.Messages;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import com.example.desktop.ui.data.SubmissionsPane;
import com.example.desktop.ui.forms.FormEditorPane;
import com.example.desktop.ui.forms.FormFillPane;
import com.example.desktop.ui.forms.FormListPane;
import com.example.dynamicform.api.FormDto;
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
    Scene scene = new Scene(status("Signing in — complete the login in your browser…"), 1100, 750);
    // Without this the error style class the field controls add has no visual effect, so a
    // rejected form would look exactly like an accepted one.
    scene.getStylesheets().add(App.class.getResource("/css/application.css").toExternalForm());
    stage.setScene(scene);
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
    String backendUrl = System.getProperty("backend.url", DEFAULT_BACKEND_URL);
    ApiClient client =
        new ApiClient(backendUrl, session::accessToken, JsonMapperFactory.create());
    FormApi formApi = new FormApi(client);
    FormDataApi dataApi = new FormDataApi(client);

    AppState state = new AppState();
    state.session().set(session);

    FormListPane list = new FormListPane(formApi, state);
    FormFillPane fill = new FormFillPane(dataApi, state);
    SubmissionsPane submissions = new SubmissionsPane(dataApi, formApi, state);
    FormEditorPane editor = state.admin().get() ? new FormEditorPane(formApi, state) : null;
    if (editor != null) {
      // A created or renamed form changes what the list should show.
      editor.setOnSaved(saved -> list.refresh());
    }
    // The panes never see each other: a selection published to AppState is what loads the form,
    // and the fetch runs off the FX thread.
    state
        .selectedFormKey()
        .addListener((obs, old, formKey) -> loadForm(formApi, fill, editor, formKey));

    layout =
        new DockLayoutManager(
            new SnapFX(),
            layoutStore(),
            nodeId ->
                switch (nodeId) {
                  case DockLayoutManager.FORM_LIST -> list;
                  case DockLayoutManager.FORM_FILL -> fill;
                  case DockLayoutManager.SUBMISSIONS -> submissions;
                  // Null for a non-admin, which is how the editor stays out of the layout.
                  case DockLayoutManager.FORM_EDITOR -> editor;
                  default -> null;
                });

    stage.setTitle("Dynamic Form Desktop" + (session.isAdmin() ? " (admin)" : ""));
    stage.getScene().setRoot(layout.build(stage));
    list.refresh();
    submissions.refresh();
  }

  private static void loadForm(
      FormApi formApi, FormFillPane fill, FormEditorPane editor, String formKey) {
    if (formKey == null || formKey.isBlank()) {
      return;
    }
    Task<FormDto> load =
        new Task<>() {
          @Override
          protected FormDto call() {
            return formApi.get(formKey);
          }
        };
    load.setOnSucceeded(
        event -> {
          fill.show(load.getValue());
          if (editor != null) {
            // An admin selecting a form gets it loaded for editing too, which is the only route to
            // updating or deleting an existing definition.
            editor.edit(load.getValue());
          }
        });
    load.setOnFailed(
        event -> {
          // Most often a form deleted since the list was last refreshed. Without this the click
          // would do nothing at all and leave the previous form on screen as if it had worked.
          Throwable cause = load.getException();
          fill.show(null);
          fill.showMessage(
              cause == null ? Messages.get("common.error") : String.valueOf(cause.getMessage()));
        });
    Thread thread = new Thread(load, "load-form");
    thread.setDaemon(true);
    thread.start();
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
