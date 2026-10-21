package com.example.desktop.ui.dock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and writes the saved dock layout.
 *
 * <p>SnapFX's {@code saveLayout()} hands back a string rather than writing anything, so the file is
 * this class's business. The layout is per user and per machine, not per server.
 */
public class LayoutStore {

  private final Path file;

  public LayoutStore(Path file) {
    this.file = file;
  }

  /**
   * Writes the layout, creating the directory on a first run.
   *
   * <p>A layout with no panes is discarded rather than written. SnapFX serializes a graph whose
   * panes have all been closed as {@code "{}"}, and persisting that would make the next launch
   * blank — and the one after it, since the blank graph serializes the same way. Keeping the last
   * usable layout instead means closing every pane costs the user their arrangement, not their
   * installation.
   */
  public void save(String layoutJson) {
    if (hasNoPanes(layoutJson)) {
      return;
    }
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, layoutJson, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot save the window layout to " + file, e);
    }
  }

  /**
   * The saved layout, or empty if there is none worth restoring.
   *
   * <p>Every failure is swallowed deliberately — this is the one place in the client where that is
   * right. A missing, truncated, hand-edited or unreadable layout file is not a reason to refuse to
   * start; the caller falls back to the default arrangement. A layout with no panes is treated the
   * same way, because SnapFX's {@code loadLayout} accepts it silently and would leave an empty
   * window.
   */
  public Optional<String> load() {
    try {
      String layout = Files.readString(file, StandardCharsets.UTF_8);
      return hasNoPanes(layout) ? Optional.empty() : Optional.of(layout);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Whether a layout string carries nothing to show: blank, an empty JSON object, or a null root.
   * These are the shapes SnapFX both produces and accepts for a graph with no nodes.
   */
  private static boolean hasNoPanes(String layoutJson) {
    if (layoutJson == null || layoutJson.isBlank()) {
      return true;
    }
    String compact = layoutJson.replaceAll("\\s", "");
    return compact.equals("{}") || compact.contains("\"root\":null");
  }
}
