package com.example.desktop;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormDataService;
import com.example.desktop.api.FormService;
import com.example.desktop.api.Reauthenticator;
import com.example.desktop.auth.AuthSession;
import com.example.desktop.auth.BrowserLauncher;
import com.example.desktop.auth.PkceLoginFlow;
import com.example.desktop.config.DesktopConfig;
import com.example.desktop.i18n.Messages;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.data.SubmissionsPane;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import com.example.desktop.ui.forms.FormEditorPane;
import com.example.desktop.ui.forms.FormFillPane;
import com.example.desktop.ui.forms.FormListPane;
import com.example.dynamicform.api.FormDto;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>The backend URL, locale and layout path come from {@link DesktopConfig}; a system property
 * such as {@code backend.url} wins over the file, so one build runs against a local server or a
 * Testcontainers stack without rebuilding.
 */
public class DesktopApp extends Application {

  private DesktopConfig config;

  private DockLayoutManager layout;

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void start(Stage stage) {
    config = DesktopConfig.load();
    Messages.setLocale(config.locale());
    if (!config.ignored().isEmpty()) {
      // Goes to the status bar once there is one.
      System.err.println(Messages.format("config.ignored", String.join(", ", config.ignored())));
    }
    String backendUrl = config.backendUrl();

    // The window goes up first, showing a status line. Logging in means three HTTP calls and then
    // waiting for a human in a browser, so it cannot run on the FX thread — and if it fails, the
    // user has to be told. Throwing out of start() only produces a stack trace on stderr, which in
    // a jpackage build nobody ever sees.
    stage.setTitle(Messages.get("app.title"));
    Scene scene = new Scene(status(Messages.get("login.inProgress")), 1100, 750);
    // Without this the error style class the field controls add has no visual effect, so a
    // rejected form would look exactly like an accepted one.
    scene
        .getStylesheets()
        .add(DesktopApp.class.getResource("/css/application.css").toExternalForm());
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
    String backendUrl = config.backendUrl();
    AppState state = new AppState();
    state.session().set(session);

    // The API layer reads the session from here rather than from AppState: it is written and read
    // off the FX thread on every request, and a re-login replaces the whole AuthSession.
    AtomicReference<AuthSession> current = new AtomicReference<>(session);
    ApiClient client =
        new ApiClient(
            backendUrl,
            () -> current.get().accessToken(),
            JsonMapperFactory.create(),
            reauthenticator(backendUrl, current, state));
    FormService formApi = new FormService(client);
    FormDataService dataApi = new FormDataService(client);

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
            new LayoutStore(config.layoutFile()),
            nodeId ->
                switch (nodeId) {
                  case DockLayoutManager.FORM_LIST -> list;
                  case DockLayoutManager.FORM_FILL -> fill;
                  case DockLayoutManager.SUBMISSIONS -> submissions;
                  // Null for a non-admin, which is how the editor stays out of the layout.
                  case DockLayoutManager.FORM_EDITOR -> editor;
                  default -> null;
                });

    // Built by the bundle rather than concatenated, so a translation can put the marker first.
    stage.setTitle(
        session.isAdmin()
            ? Messages.format("app.titleAdmin", Messages.get("app.title"))
            : Messages.get("app.title"));
    stage.getScene().setRoot(layout.build(stage));
    list.refresh();
    submissions.refresh();
  }

  private static void loadForm(
      FormService formApi, FormFillPane fill, FormEditorPane editor, String formKey) {
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

  /**
   * Runs the login flow again when the backend rejects the token, per spec 4.2 step 5. The access
   * token lives an hour, and before this a session outliving it failed every call with "not logged
   * in, or the access token has expired" and no way back but a restart.
   *
   * <p>Called on whichever background thread met the 401 — never the FX thread, since it opens the
   * browser and waits for a human. {@code ApiClient} runs one at a time, so this cannot produce two
   * browser windows.
   *
   * <p>A session with different roles does not re-dock the layout: whether the editor pane exists
   * was decided when the window was built. Signing back in as the same user, which is the case this
   * is for, keeps them the same.
   */
  private static Reauthenticator reauthenticator(
      String backendUrl, AtomicReference<AuthSession> current, AppState state) {
    return () -> {
      AuthSession fresh =
          new PkceLoginFlow(backendUrl, BrowserLauncher.systemBrowser(), JsonMapperFactory.create())
              .login();
      current.set(fresh);
      // AppState belongs to the UI, so it is only ever written on the FX thread.
      Platform.runLater(() -> state.session().set(fresh));
      return fresh.accessToken();
    };
  }

  /** Shows why the application cannot continue, then exits. */
  private void reportFatal(Throwable cause) {
    String message = cause == null ? Messages.get("common.error") : cause.getMessage();
    Alert alert =
        new Alert(
            Alert.AlertType.ERROR,
            message == null || message.isBlank() ? cause.toString() : message);
    alert.setHeaderText(Messages.get("login.failed"));
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
}
