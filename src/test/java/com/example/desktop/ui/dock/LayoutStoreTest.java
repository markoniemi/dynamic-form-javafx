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

    assertDoesNotThrow(() -> store.save("{}"));
    assertEquals("{}", store.load().orElseThrow());
  }

  @Test
  void loadReturnsEmptyWhenThePathIsADirectoryInsteadOfAFile() throws Exception {
    Path path = dir.resolve("layout.json");
    Files.createDirectory(path);

    assertTrue(
        new LayoutStore(path).load().isEmpty(), "an unreadable layout must not crash startup");
  }
}
