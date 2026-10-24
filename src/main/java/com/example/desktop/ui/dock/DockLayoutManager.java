package com.example.desktop.ui.dock;

import com.example.desktop.i18n.Messages;
import java.util.List;
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
  private final PaneFactory panes;

  public DockLayoutManager(SnapFX snapFX, LayoutStore store, PaneFactory panes) {
    this.snapFX = snapFX;
    this.store = store;
    this.panes = panes;
  }

  /**
   * Initialises SnapFX, restores the saved layout if there is a usable one, and returns the root.
   */
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

  /** The current layout as SnapFX serializes it, without writing it anywhere. */
  public String currentLayout() {
    return snapFX.saveLayout();
  }

  /** The docked node with this layout id, or null if the layout does not contain one. */
  public DockNode nodeById(String id) {
    DockElement element = snapFX.getDockGraph().findElementByLayoutId(id);
    return element instanceof DockNode node ? node : null;
  }

  /** Removes one docked pane, as a user does with its close button. */
  public void close(DockNode node) {
    if (node != null) {
      snapFX.remove(node);
    }
  }

  /**
   * Removes every docked pane, as a user does by clicking each close button. Exists so the
   * blank-layout recovery path can be tested rather than only reasoned about.
   */
  public void closeAllPanes() {
    for (String id : List.of(FORM_LIST, FORM_FILL, SUBMISSIONS, FORM_EDITOR)) {
      DockNode node = nodeById(id);
      if (node != null) {
        snapFX.remove(node);
      }
    }
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
                stripPanesTheFactoryDeclines();
                attachPaneContent();
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
      snapFX.dock(editor, title(FORM_EDITOR), fill, DockPosition.CENTER).setLayoutId(FORM_EDITOR);
    }
  }

  /**
   * Removes any node the factory will not build.
   *
   * <p>A saved layout can outlive the session that made it — an admin's layout opened by a plain
   * user still names the editor — so the file is not trusted. The factory is the single authority
   * on what this session may see, which keeps the default and restored paths from disagreeing.
   */
  private void stripPanesTheFactoryDeclines() {
    for (String id : List.of(FORM_LIST, FORM_FILL, SUBMISSIONS, FORM_EDITOR)) {
      if (panes.create(id) != null) {
        continue;
      }
      DockElement element = snapFX.getDockGraph().findElementByLayoutId(id);
      if (element instanceof DockNode node) {
        snapFX.remove(node);
      }
    }
  }

  /**
   * Puts this session's panes into the nodes a restored layout produced.
   *
   * <p>Required because SnapFX asks the node factory for content by {@code dockNodeId} — the random
   * UUID it assigns — and not by the stable layout id the factory is keyed on. Every lookup
   * therefore misses, and SnapFX fills the node with a placeholder reading "Unavailable node
   * restored as placeholder" instead: a window of empty panels, which is what the second launch of
   * the real application showed. The node itself survives with its layout id intact, so the content
   * can simply be put back here, by the id this class already owns.
   *
   * <p>Done by stable id rather than by translating UUIDs, so nothing depends on how SnapFX names
   * or serializes its nodes.
   */
  private void attachPaneContent() {
    for (String id : List.of(FORM_LIST, FORM_FILL, SUBMISSIONS, FORM_EDITOR)) {
      DockNode node = nodeById(id);
      if (node == null) {
        continue;
      }
      Node content = panes.create(id);
      if (content != null) {
        node.setContent(content);
      }
    }
  }

  /**
   * Rebuilds a node named by a saved layout.
   *
   * <p>On a restore SnapFX calls this with a {@code dockNodeId}, so an id this method recognises
   * only ever arrives from {@link #stripPanesTheFactoryDeclines()}; {@link #attachPaneContent()} is
   * what actually fills a restored pane. Returning null is how a stale layout degrades: SnapFX
   * keeps the node and substitutes a placeholder rather than failing the load.
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
