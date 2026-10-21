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

  /** Writes the layout, creating the directory on a first run. */
  public void save(String layoutJson) {
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
   * The saved layout, or empty if there is none to read.
   *
   * <p>Every failure is swallowed deliberately — this is the one place in the client where that is
   * right. A missing, truncated, hand-edited or unreadable layout file is not a reason to refuse to
   * start; the caller falls back to the default arrangement.
   */
  public Optional<String> load() {
    try {
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
