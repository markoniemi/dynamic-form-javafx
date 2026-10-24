package com.example.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.config.DesktopConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackPropertiesTest {
  @TempDir Path dir;

  @Test
  void theWrittenFileIsOneDesktopConfigCanRead() throws IOException {
    Path file =
        StackLauncher.writeProperties(
            dir.resolve("dev-stack.properties"), "http://localhost:32769");
    assertEquals("http://localhost:32769", DesktopConfig.load(file, new Properties()).backendUrl());
    assertTrue(DesktopConfig.load(file, new Properties()).ignored().isEmpty());
  }

  @Test
  void writingTwiceReplacesTheOldUrl() throws IOException {
    Path file = dir.resolve("dev-stack.properties");
    StackLauncher.writeProperties(file, "http://localhost:11111");
    StackLauncher.writeProperties(file, "http://localhost:2");
    assertEquals("backend.url=http://localhost:2" + System.lineSeparator(), Files.readString(file));
    assertEquals("http://localhost:2", DesktopConfig.load(file, new Properties()).backendUrl());
  }

  @Test
  void deleteQuietlyRemovesThePublishedFileAndToleratesAbsence() throws IOException {
    Path file = StackLauncher.writeProperties(dir.resolve("dev-stack.properties"), "http://x:1");
    StackLauncher.deleteQuietly(file);
    assertFalse(Files.exists(file));
    StackLauncher.deleteQuietly(file);
  }
}
