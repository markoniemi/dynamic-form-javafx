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

  @Test
  void anIllegalLayoutPathFallsBackToTheDefaultAndIsReported() {
    Properties overrides = new Properties();
    overrides.setProperty("layout.file", "bad\0path");
    DesktopConfig config = DesktopConfig.load(dir.resolve("absent.properties"), overrides);
    assertEquals(defaultLayout(), config.layoutFile());
    assertEquals(List.of("layout.file"), config.ignored());
  }

  @Test
  void anIllegalConfigFilePropertyFallsBackToTheDefaultLocationAndIsReported() {
    String old = System.getProperty("config.file");
    System.setProperty("config.file", "bad\0path");
    try {
      DesktopConfig config = DesktopConfig.load();
      assertTrue(config.ignored().contains("config.file"));
    } finally {
      if (old == null) {
        System.clearProperty("config.file");
      } else {
        System.setProperty("config.file", old);
      }
    }
  }

  @Test
  void aNamedConfigFileThatDoesNotExistIsReported() {
    String old = System.getProperty("config.file");
    System.setProperty("config.file", dir.resolve("typo.properties").toString());
    try {
      assertEquals(List.of("config.file"), DesktopConfig.load().ignored());
    } finally {
      if (old == null) {
        System.clearProperty("config.file");
      } else {
        System.setProperty("config.file", old);
      }
    }
  }

  @Test
  void aMalformedUnicodeEscapeIsReportedAsTheConfigFile() throws IOException {
    DesktopConfig config = DesktopConfig.load(write("backend.url=\\u12\n"), new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertEquals(List.of("config.file"), config.ignored());
  }

  @Test
  void aValidLocaleIsAccepted() throws IOException {
    DesktopConfig config = DesktopConfig.load(write("locale=fi-FI\n"), new Properties());
    assertEquals(Locale.forLanguageTag("fi-FI"), config.locale());
    assertTrue(config.ignored().isEmpty());
  }

  @Test
  void theLayoutPathDefaultsToTheUserHome() {
    DesktopConfig config = DesktopConfig.load(dir.resolve("absent.properties"), new Properties());
    assertEquals(defaultLayout(), config.layoutFile());
  }

  @Test
  void anHttpsUrlIsAccepted() throws IOException {
    DesktopConfig config =
        DesktopConfig.load(write("backend.url=https://example.test\n"), new Properties());
    assertEquals("https://example.test", config.backendUrl());
    assertTrue(config.ignored().isEmpty());
  }

  @Test
  void anInvalidSystemPropertyOverrideFallsBackToTheDefaultAndIsReported() throws IOException {
    Properties overrides = new Properties();
    overrides.setProperty("backend.url", "nonsense");
    DesktopConfig config = DesktopConfig.load(write("backend.url=http://from-file:1\n"), overrides);
    assertEquals("http://localhost:8080", config.backendUrl());
    assertEquals(List.of("backend.url"), config.ignored());
  }

  private static Path defaultLayout() {
    return Path.of(System.getProperty("user.home"), ".dynamic-form-desktop", "layout.json");
  }

  private Path write(String text) throws IOException {
    return Files.writeString(dir.resolve("config.properties"), text);
  }
}
