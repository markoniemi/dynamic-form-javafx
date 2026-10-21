package com.example.desktop.ui.dock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure file IO, so no JavaFX toolkit is needed. The point of these is that nothing the saved layout
 * can be — absent, nested, unreadable — may stop the application from starting.
 */
class LayoutStoreTest {

  @TempDir Path dir;

  @Test
  void savesAndLoadsTheLayoutString() {
    LayoutStore store = new LayoutStore(dir.resolve("layout.json"));

    store.save("{\"root\":\"x\"}");

    assertEquals("{\"root\":\"x\"}", store.load().orElseThrow());
  }

  @Test
  void loadReturnsEmptyOnAFirstRunWithNoFile() {
    assertTrue(new LayoutStore(dir.resolve("absent.json")).load().isEmpty());
  }

  @Test
  void saveCreatesMissingParentDirectories() {
    LayoutStore store = new LayoutStore(dir.resolve("nested/deeper/layout.json"));

    assertDoesNotThrow(() -> store.save(A_REAL_LAYOUT));
    assertEquals(A_REAL_LAYOUT, store.load().orElseThrow());
  }

  @Test
  void loadRejectsALayoutWithNoPanes() {
    // SnapFX serializes a graph whose panes have all been closed as exactly "{}", and its
    // loadLayout accepts that without complaint — leaving a window with nothing in it.
    LayoutStore store = new LayoutStore(dir.resolve("layout.json"));
    store.save(A_REAL_LAYOUT);
    writeDirectly("{}");

    assertTrue(store.load().isEmpty(), "an empty layout must not count as one to restore");
  }

  @Test
  void loadRejectsALayoutWhoseRootIsNull() {
    LayoutStore store = new LayoutStore(dir.resolve("layout.json"));
    writeDirectly("{\"root\":null}");

    assertTrue(store.load().isEmpty());
  }

  @Test
  void saveRefusesToPersistALayoutWithNoPanes() {
    LayoutStore store = new LayoutStore(dir.resolve("layout.json"));
    store.save(A_REAL_LAYOUT);

    store.save("{}");

    assertEquals(
        A_REAL_LAYOUT,
        store.load().orElseThrow(),
        "closing every pane must not overwrite the last usable layout");
  }

  /** Shaped like what SnapFX actually writes: a root element carrying a node. */
  private static final String A_REAL_LAYOUT =
      "{\"root\":{\"type\":\"node\",\"id\":\"form-list\",\"title\":\"Forms\"}}";

  private void writeDirectly(String content) {
    try {
      Files.writeString(dir.resolve("layout.json"), content);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void loadReturnsEmptyWhenThePathIsADirectoryInsteadOfAFile() throws Exception {
    Path path = dir.resolve("layout.json");
    Files.createDirectory(path);

    assertTrue(
        new LayoutStore(path).load().isEmpty(), "an unreadable layout must not crash startup");
  }
}
