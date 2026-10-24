package com.example.desktop;

import com.example.desktop.api.FormDataService;
import com.example.desktop.api.FormService;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.data.SubmissionsPane;
import com.example.desktop.ui.dock.DockLayoutManager;
import com.example.desktop.ui.dock.PaneFactory;
import com.example.desktop.ui.forms.FormEditorPane;
import com.example.desktop.ui.forms.FormFillPane;
import com.example.desktop.ui.forms.FormListPane;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The panes and state of one signed-in session. Signing out retires it; a background task that
 * finishes afterwards finds {@link #isCurrent()} false and {@link #runIfCurrent} does nothing, so
 * it cannot write into the next session's panes.
 *
 * <p>Whether the editor exists is decided here, from the roles of the session, which is why a new
 * session means a new instance.
 */
final class SessionPanes {

  private final AppState state;
  private final FormListPane list;
  private final FormFillPane fill;
  private final SubmissionsPane submissions;
  private final FormEditorPane editor;
  private final AtomicBoolean retired = new AtomicBoolean();

  SessionPanes(FormService forms, FormDataService data, AppState state) {
    this.state = state;
    this.list = new FormListPane(forms, state);
    this.fill = new FormFillPane(data, state);
    this.submissions = new SubmissionsPane(data, forms, state);
    this.editor = state.admin().get() ? new FormEditorPane(forms, state) : null;
    if (editor != null) {
      // A created or renamed form changes what the list should show.
      editor.setOnSaved(saved -> list.refreshSilently());
    }
  }

  AppState state() {
    return state;
  }

  PaneFactory paneFactory() {
    return nodeId ->
        switch (nodeId) {
          case DockLayoutManager.FORM_LIST -> list;
          case DockLayoutManager.FORM_FILL -> fill;
          case DockLayoutManager.SUBMISSIONS -> submissions;
          // Null for a non-admin, which is how the editor stays out of the layout.
          case DockLayoutManager.FORM_EDITOR -> editor;
          default -> null;
        };
  }

  FormListPane list() {
    return list;
  }

  FormFillPane fill() {
    return fill;
  }

  SubmissionsPane submissions() {
    return submissions;
  }

  /** Null for a non-admin. */
  FormEditorPane editor() {
    return editor;
  }

  boolean isCurrent() {
    return !retired.get();
  }

  void retire() {
    retired.set(true);
  }

  /**
   * The guard every {@code setOnSucceeded} and {@code setOnFailed} body is wrapped in.
   *
   * <p>What it protects against: every session builds its own {@code AppState} and panes, so a
   * retired handler writing into them harms nothing today. The guard saves needless work on panes
   * nobody sees, and keeps a retired session's result away from any live UI should something ever
   * be shared between sessions. It does not make the panes' own internal handlers safe.
   */
  void runIfCurrent(Runnable block) {
    if (isCurrent()) {
      block.run();
    }
  }
}
