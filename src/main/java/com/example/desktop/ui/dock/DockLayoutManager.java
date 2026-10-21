package com.example.desktop.ui.dock;

import com.example.desktop.i18n.Messages;
import com.example.desktop.state.AppState;
import javafx.scene.Node;
import javafx.scene.Parent;
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
 * <p>Pane content comes from a {@link PaneFactory} the caller supplies, so this class knows ids and
 * positions but nothing about the API. A factory returning null for an id is how a non-admin ends
 * up with no editor.
 */
public class DockLayoutManager {

  /** Stable ids. Layout persistence is keyed on these, so they must not change casually. */
  public static final String FORM_LIST = "form-list";

  public static final String FORM_FILL = "form-fill";
  public static final String FORM_EDITOR = "form-editor";
  public static final String SUBMISSIONS = "submissions";

  private final SnapFX snapFX;
  private final LayoutStore store;
  private final AppState state;
  private final PaneFactory panes;

  public DockLayoutManager(SnapFX snapFX, LayoutStore store, AppState state, PaneFactory panes) {
    this.snapFX = snapFX;
    this.store = store;
    this.state = state;
    this.panes = panes;
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
                if (snapFX.getDockGraph().getRoot() == null) {
                  // loadLayout accepts a graph with nothing in it without complaining, which would
                  // leave an empty window. LayoutStore screens the shapes SnapFX is known to write;
                  // this catches anything else that deserializes to nothing.
                  return false;
                }
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

  /**
   * The arrangement the spec asks for: list on the left, form in the centre, submissions below it.
   *
   * <p>Each pane is positioned relative to the one it belongs beside, not all relative to the list.
   * Docking twice to the list's RIGHT splits the list's own cell instead of adding a column, which
   * puts the editor in a quarter-width strip next to the list and the submissions under that pair.
   */
  private void dockDefaults() {
    DockNode list = snapFX.dock(panes.create(FORM_LIST), title(FORM_LIST));
    list.setLayoutId(FORM_LIST);

    DockNode fill =
        snapFX.dock(panes.create(FORM_FILL), title(FORM_FILL), list, DockPosition.RIGHT);
    fill.setLayoutId(FORM_FILL);

    snapFX
        .dock(panes.create(SUBMISSIONS), title(SUBMISSIONS), fill, DockPosition.BOTTOM)
        .setLayoutId(SUBMISSIONS);

    Node editor = panes.create(FORM_EDITOR);
    if (editor != null) {
      // Tabbed with the form rather than given its own column: the two are alternative views of
      // the same selected form, and never useful side by side.
      snapFX
          .dock(editor, title(FORM_EDITOR), fill, DockPosition.CENTER)
          .setLayoutId(FORM_EDITOR);
    }
  }

  /**
   * A saved layout may still carry the editor from a session where this user was an admin, or from
   * a different user entirely, so strip it rather than trusting the file.
   */
  private void hideEditorFromNonAdmins() {
    if (state.admin().get()) {
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
    Node content = panes.create(id);
    return content == null ? null : new DockNode(id, content, title(id));
  }

  /** The dock node's tab label, from the shared bundle so it matches the web client's wording. */
  private static String title(String id) {
    return switch (id) {
      case FORM_LIST -> Messages.get("navigation.forms");
      case FORM_FILL -> Messages.get("form.submit");
      case SUBMISSIONS -> Messages.get("navigation.submissions");
      case FORM_EDITOR -> Messages.get("navigation.createForm");
      default -> id;
    };
  }
}
