package com.example.desktop.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopConfigTest {
  @TempDir Path dir;

  @Test
  void aMissingFileGivesTheDefaults() {
    DesktopConfig config = DesktopConfig.load(dir.resolve("absent.properties"), new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertTrue(config.ignored().isEmpty());
  }

  @Test
  void theFileSuppliesTheBackendUrlAndTheLayoutPath() throws IOException {
    Path file = write("backend.url=http://example.test:9999\nlayout.file=/tmp/l.json\n");
    DesktopConfig config = DesktopConfig.load(file, new Properties());
    assertEquals("http://example.test:9999", config.backendUrl());
    assertEquals(Path.of("/tmp/l.json"), config.layoutFile());
  }

  @Test
  void aSystemPropertyWinsOverTheFile() throws IOException {
    Path file = write("backend.url=http://from-file:1\n");
    Properties overrides = new Properties();
    overrides.setProperty("backend.url", "http://from-property:2");
    assertEquals("http://from-property:2", DesktopConfig.load(file, overrides).backendUrl());
  }

  @Test
  void anUnknownKeyIsIgnoredAndReported() throws IOException {
    Path file = write("backend.url=http://example.test:1\nnonsense=1\n");
    DesktopConfig config = DesktopConfig.load(file, new Properties());
    assertEquals("http://example.test:1", config.backendUrl());
    assertEquals(List.of("nonsense"), config.ignored());
  }

  @Test
  void aBackendUrlWithoutASchemeFallsBackToTheDefaultAndIsReported() throws IOException {
    // Reaches URI.create inside ApiClient otherwise, as an IllegalArgumentException that never
    // mentions the config file.
    DesktopConfig config =
        DesktopConfig.load(write("backend.url=localhost:8080\n"), new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertEquals(List.of("backend.url"), config.ignored());
  }

  @Test
  void aBlankBackendUrlFallsBackToTheDefault() throws IOException {
    assertEquals(
        "http://localhost:8080",
        DesktopConfig.load(write("backend.url=   \n"), new Properties()).backendUrl());
  }

  @Test
  void anUnreadableFileDoesNotStopStartup() throws IOException {
    // A directory where a file is expected: reading it throws IOException, and a config
    // problem must never be the reason the window does not appear.
    Path asDirectory = Files.createDirectory(dir.resolve("config.properties"));
    DesktopConfig config = DesktopConfig.load(asDirectory, new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertEquals(List.of("config.file"), config.ignored());
  }

  @Test
  void anUnknownLocaleTagFallsBackToTheSystemLocale() throws IOException {
    DesktopConfig config = DesktopConfig.load(write("locale=not a tag\n"), new Properties());
    assertEquals(Locale.getDefault(), config.locale());
    assertEquals(List.of("locale"), config.ignored());
  }

  private Path write(String text) throws IOException {
    return Files.writeString(dir.resolve("config.properties"), text);
  }
}
