package com.example.desktop.ui.dock;

import com.example.desktop.auth.AuthSession;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.snapfx.SnapFX;
import org.snapfx.model.DockElement;
import org.snapfx.model.DockNode;
import org.snapfx.model.DockPosition;
import org.snapfx.persistence.DockLayoutLoadException;

/**
 * Builds and persists the dockable window layout.
 *
 * <p>This is the only class in the project that may import {@code org.snapfx}. SnapFX is a preview
 * library and is not on Maven Central, so keeping it behind one class keeps a swap cheap.
 *
 * <p>The panes are placeholders for now; the real ones replace the content of these nodes without
 * changing their ids.
 */
public class DockLayoutManager {

  /** Stable ids. Layout persistence is keyed on these, so they must not change casually. */
  public static final String FORM_LIST = "form-list";

  public static final String FORM_FILL = "form-fill";
  public static final String FORM_EDITOR = "form-editor";
  public static final String SUBMISSIONS = "submissions";

  private final SnapFX snapFX;
  private final LayoutStore store;
  private final AuthSession session;

  public DockLayoutManager(SnapFX snapFX, LayoutStore store, AuthSession session) {
    this.snapFX = snapFX;
    this.store = store;
    this.session = session;
  }

  /** Initialises SnapFX, restores the saved layout if there is a usable one, and returns the root. */
  public Parent build(Stage stage) {
    snapFX.initialize(stage);
    snapFX.setNodeFactory(this::createNode);

    if (!restoreSaved()) {
      dockDefaults();
    }
    return snapFX.buildLayout();
  }

  /** Hands the current layout to the store. Call from the application's stop hook. */
  public void saveLayout() {
    store.save(snapFX.saveLayout());
  }

  /**
   * @return true if a saved layout was restored; false if the caller should dock the defaults
   */
  private boolean restoreSaved() {
    return store
        .load()
        .map(
            saved -> {
              try {
                snapFX.loadLayout(saved);
                hideEditorFromNonAdmins();
                return true;
              } catch (DockLayoutLoadException e) {
                // A layout from an older build, or a hand-edited one. Starting with the default
                // arrangement is always better than refusing to start.
                return false;
              }
            })
        .orElse(false);
  }

  private void dockDefaults() {
    DockNode list = snapFX.dock(placeholder("Forms"), "Forms");
    list.setLayoutId(FORM_LIST);
    snapFX.dock(placeholder("Form"), "Form", list, DockPosition.RIGHT).setLayoutId(FORM_FILL);
    snapFX
        .dock(placeholder("Submissions"), "Submissions", list, DockPosition.BOTTOM)
        .setLayoutId(SUBMISSIONS);
    if (session.isAdmin()) {
      snapFX
          .dock(placeholder("Editor"), "Editor", list, DockPosition.RIGHT)
          .setLayoutId(FORM_EDITOR);
    }
  }

  /**
   * A saved layout may still carry the editor from a session where this user was an admin, or from
   * a different user entirely, so strip it rather than trusting the file.
   */
  private void hideEditorFromNonAdmins() {
    if (session.isAdmin()) {
      return;
    }
    DockElement editor = snapFX.getDockGraph().findElementByLayoutId(FORM_EDITOR);
    if (editor instanceof DockNode node) {
      snapFX.remove(node);
    }
  }

  /**
   * Rebuilds a node named by a saved layout. Returning null for an unknown id is how a stale
   * layout degrades: SnapFX drops the node instead of failing the load.
   */
  private DockNode createNode(String id) {
    return switch (id) {
      case FORM_LIST -> new DockNode(FORM_LIST, placeholder("Forms"), "Forms");
      case FORM_FILL -> new DockNode(FORM_FILL, placeholder("Form"), "Form");
      case SUBMISSIONS -> new DockNode(SUBMISSIONS, placeholder("Submissions"), "Submissions");
      case FORM_EDITOR ->
          session.isAdmin() ? new DockNode(FORM_EDITOR, placeholder("Editor"), "Editor") : null;
      default -> null;
    };
  }

  private static Node placeholder(String text) {
    Label label = new Label(text);
    label.setAlignment(Pos.CENTER);
    label.setMaxWidth(Double.MAX_VALUE);
    label.setMaxHeight(Double.MAX_VALUE);
    return label;
  }
}
