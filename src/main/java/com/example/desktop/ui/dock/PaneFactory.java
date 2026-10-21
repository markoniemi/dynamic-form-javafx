package com.example.desktop.ui.dock;

import javafx.scene.Node;

/**
 * Supplies the content for a dock node id.
 *
 * <p>It exists so {@link DockLayoutManager} stays ignorant of the API and the panes: the manager
 * knows ids and positions, the caller knows how to build a pane.
 */
@FunctionalInterface
public interface PaneFactory {

  /**
   * The content for an id, or null if this factory will not build it — which is how a non-admin
   * ends up with no editor, whether the id came from the default layout or a saved one.
   */
  Node create(String nodeId);
}
