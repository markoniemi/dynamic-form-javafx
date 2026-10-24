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
import com.example.desktop.ui.common.LoginPane;
import com.example.desktop.ui.common.MainMenuBar;
import com.example.desktop.ui.common.StatusBar;
import com.example.desktop.ui.data.SubmissionsPane;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.LayoutStore;
import com.example.desktop.ui.forms.FormListPane;
import com.example.dynamicform.api.FormDto;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Application entry point.
 *
 * <p>The backend URL, locale and layout path come from {@link DesktopConfig}; a system property
 * such as {@code backend.url} wins over the file, so one build runs against a local server or a
 * Testcontainers stack without rebuilding.
 *
 * <p>The window has two states: a {@link LoginPane}, and the main window built fresh for each
 * session. Signing out and in again rebuilds the main window, which is how a session with other
 * roles gets the right panes.
 */
public class DesktopApp extends Application {

  private DesktopConfig config;

  private DockLayoutManager layout;

  /** The live session's panes; replaced on every sign-in and retired on sign-out. */
  private final AtomicReference<SessionPanes> panes = new AtomicReference<>();

  public DesktopApp() {}

  /** For tests, which must not read the user's configuration or write the user's layout file. */
  DesktopApp(DesktopConfig config) {
    this.config = config;
  }

  public static void main(String[] args) {
    launch(args);
  }

  DockLayoutManager layoutManager() {
    return layout;
  }

  SessionPanes panes() {
    return panes.get();
  }

  @Override
  public void start(Stage stage) {
    config = DesktopConfig.load();
    Messages.setLocale(config.locale());
    if (!config.ignored().isEmpty()) {
      // The record: written even when login never gets as far as a window. The status bar, once
      // there is one, is what the user reads.
      System.err.println(Messages.format("config.ignored", String.join(", ", config.ignored())));
    }
    stage.setTitle(Messages.get("app.title"));
    Scene scene = new Scene(new BorderPane(), 1100, 750);
    // Without this the error style class the field controls add has no visual effect, so a
    // rejected form would look exactly like an accepted one.
    scene
        .getStylesheets()
        .add(DesktopApp.class.getResource("/css/application.css").toExternalForm());
    stage.setScene(scene);
    showLogin(stage);
    stage.show();
  }

  /**
   * Puts the sign-in screen up. Logging in means three HTTP calls and then waiting for a human in a
   * browser, so it cannot run on the FX thread; and if it fails the reason is shown here and the
   * user can try again.
   */
  void showLogin(Stage stage) {
    stage.setTitle(Messages.get("app.title"));
    String backendUrl = config().backendUrl();
    LoginPane[] pane = new LoginPane[1];
    pane[0] =
        new LoginPane(
            () -> {
              pane[0].setBusy(true);
              Task<AuthSession> login =
                  new Task<>() {
                    @Override
                    protected AuthSession call() {
                      return new PkceLoginFlow(
                              backendUrl,
                              BrowserLauncher.systemBrowser(),
                              JsonMapperFactory.create())
                          .login();
                    }
                  };
              login.setOnSucceeded(event -> showMainWindow(stage, login.getValue()));
              login.setOnFailed(
                  event -> {
                    pane[0].setBusy(false);
                    Throwable cause = login.getException();
                    String reason =
                        cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                            ? String.valueOf(cause)
                            : cause.getMessage();
                    pane[0].showMessage(Messages.get("login.failed") + ": " + reason);
                  });
              Thread thread = new Thread(login, "login");
              thread.setDaemon(true);
              thread.start();
            });
    stage.getScene().setRoot(pane[0]);
  }

  /** Builds everything fresh for this session; see the class comment for why. */
  void showMainWindow(Stage stage, AuthSession session) {
    String backendUrl = config().backendUrl();
    AppState state = new AppState();
    state.session().set(session);

    // The API layer reads the session from here rather than from AppState: it is written and read
    // off the FX thread on every request, and a re-login replaces the whole AuthSession.
    AtomicReference<AuthSession> current = new AtomicReference<>(session);
    AtomicReference<SessionPanes> mine = new AtomicReference<>();
    ApiClient client =
        new ApiClient(
            backendUrl,
            () -> current.get().accessToken(),
            JsonMapperFactory.create(),
            reauthenticator(backendUrl, current, state, mine));
    FormService formApi = new FormService(client);
    FormDataService dataApi = new FormDataService(client);

    SessionPanes sessionPanes = new SessionPanes(formApi, dataApi, state);
    mine.set(sessionPanes);
    panes.set(sessionPanes);
    // The panes never see each other: a selection published to AppState is what loads the form,
    // and the fetch runs off the FX thread.
    state
        .selectedFormKey()
        .addListener((obs, old, formKey) -> loadForm(formApi, sessionPanes, formKey));

    // A new manager, and with it a new SnapFX, for every session: SnapFX binds to the stage it is
    // given, and build() registers a listener.
    layout =
        DockLayoutManager.create(
            new LayoutStore(config().layoutFile()), sessionPanes.paneFactory());

    // Built by the bundle rather than concatenated, so a translation can put the marker first.
    stage.setTitle(
        session.isAdmin()
            ? Messages.format("app.titleAdmin", Messages.get("app.title"))
            : Messages.get("app.title"));
    stage.getScene().setRoot(windowRoot(stage, state));
    startLoad(state, config(), sessionPanes.list(), sessionPanes.submissions());
  }

  private DesktopConfig config() {
    if (config == null) {
      config = DesktopConfig.load();
    }
    return config;
  }

  /**
   * The first load of the data panes, and the one place a startup message is put on the status
   * line. The loads are silent: they were not asked for, and their counts would overwrite a warning
   * the user has not had time to read.
   *
   * <p>Public only because {@code StartupIT} lives in another package.
   */
  public static void startLoad(
      AppState state, DesktopConfig config, FormListPane list, SubmissionsPane submissions) {
    if (!config.ignored().isEmpty()) {
      state.status().set(Messages.format("config.ignored", String.join(", ", config.ignored())));
    }
    list.refreshSilently();
    submissions.refreshSilently();
  }

  /** The window content: the menubar on top, the dock in the centre, the status bar below. */
  private Parent windowRoot(Stage stage, AppState state) {
    DockLayoutManager manager = layout;
    BorderPane root = new BorderPane(manager.build(stage));
    root.setTop(
        new MainMenuBar(
            new MainMenuBar.Actions() {
              @Override
              public void reopen(String layoutId) {
                manager.reopen(layoutId);
              }

              @Override
              public void resetLayout() {
                root.setCenter(manager.resetToDefaults());
              }

              @Override
              public void signOut() {
                DesktopApp.this.signOut(stage);
              }

              @Override
              public void exit() {
                Platform.exit();
              }

              @Override
              public List<String> reopenable() {
                return manager.reopenableIds();
              }
            }));
    root.setBottom(new StatusBar(state));
    return root;
  }

  /**
   * Saves the layout, retires the session's panes so no task still in flight can write into the
   * next session, and goes back to the sign-in screen.
   */
  void signOut(Stage stage) {
    if (layout != null) {
      layout.saveLayout();
      layout = null;
    }
    SessionPanes retired = panes.getAndSet(null);
    if (retired != null) {
      retired.retire();
    }
    showLogin(stage);
  }

  private static void loadForm(FormService formApi, SessionPanes panes, String formKey) {
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
        event ->
            panes.runIfCurrent(
                () -> {
                  panes.fill().show(load.getValue());
                  if (panes.editor() != null) {
                    // An admin selecting a form gets it loaded for editing too, which is the only
                    // route to updating or deleting an existing definition.
                    panes.editor().edit(load.getValue());
                  }
                }));
    load.setOnFailed(
        event ->
            panes.runIfCurrent(
                () -> {
                  // Most often a form deleted since the list was last refreshed. Without this the
                  // click would do nothing at all and leave the previous form on screen as if it
                  // had worked.
                  Throwable cause = load.getException();
                  panes.fill().show(null);
                  panes
                      .fill()
                      .showMessage(
                          cause == null
                              ? Messages.get("common.error")
                              : String.valueOf(cause.getMessage()));
                }));
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
   * <p>Signing back in as the same user, which is the case this is for, keeps the roles the same; a
   * session with other roles comes from signing out and in, which rebuilds the window.
   */
  private static Reauthenticator reauthenticator(
      String backendUrl,
      AtomicReference<AuthSession> current,
      AppState state,
      AtomicReference<SessionPanes> mine) {
    return () -> {
      AuthSession fresh =
          new PkceLoginFlow(backendUrl, BrowserLauncher.systemBrowser(), JsonMapperFactory.create())
              .login();
      current.set(fresh);
      // AppState belongs to the UI, so it is only ever written on the FX thread; and not at all
      // once the session it belongs to has been signed out.
      Platform.runLater(() -> mine.get().runIfCurrent(() -> state.session().set(fresh)));
      return fresh.accessToken();
    };
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
}
