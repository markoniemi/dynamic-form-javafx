package com.example.desktop.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * User settings: the backend address, the locale and where the dock layout lives.
 *
 * <p>Three keys and no more. The OAuth2 callback port is deliberately not one of them: the
 * registration the backend publishes is a fixed {@code http://localhost:8123/callback} and the URI
 * must match byte for byte.
 *
 * <p>A system property of the same name wins over the file, so {@code -Dbackend.url=...} keeps
 * working and the Testcontainers launchers need no change. Nothing here throws: a config problem
 * must never be the reason the window does not appear. Bad or unknown entries are listed in {@link
 * #ignored()} for the caller to report.
 */
public final class DesktopConfig {

  public static final String DEFAULT_BACKEND_URL = "http://localhost:8080";

  private static final String BACKEND_URL = "backend.url";
  private static final String LOCALE = "locale";
  private static final String LAYOUT_FILE = "layout.file";
  private static final Set<String> KNOWN = Set.of(BACKEND_URL, LOCALE, LAYOUT_FILE);

  private final String backendUrl;
  private final Locale locale;
  private final Path layoutFile;
  private final List<String> ignored;

  private DesktopConfig(String backendUrl, Locale locale, Path layoutFile, List<String> ignored) {
    this.backendUrl = backendUrl;
    this.locale = locale;
    this.layoutFile = layoutFile;
    this.ignored = List.copyOf(ignored);
  }

  /**
   * Reads {@code -Dconfig.file} if set, else {@code ~/.dynamic-form-desktop/config.properties},
   * else the defaults. System properties override the file.
   */
  public static DesktopConfig load() {
    List<String> ignored = new ArrayList<>();
    Path defaultFile = defaultDir().resolve("config.properties");
    String named = System.getProperty("config.file");
    Path file = defaultFile;
    boolean explicit = false;
    if (named != null && !named.isBlank()) {
      Path parsed = parsePath(named);
      if (parsed == null) {
        ignored.add("config.file");
      } else {
        file = parsed;
        explicit = true;
      }
    }
    return load(file, explicit, System.getProperties(), ignored);
  }

  /** As {@link #load()}, from an explicit file and an explicit set of overriding properties. */
  public static DesktopConfig load(Path file, Properties overrides) {
    return load(file, false, overrides, new ArrayList<>());
  }

  private static DesktopConfig load(
      Path file, boolean explicit, Properties overrides, List<String> ignored) {
    Properties values = new Properties();
    if (!Files.exists(file)) {
      // Only a file the user named is worth reporting; no file at the default place is normal.
      if (explicit) {
        ignored.add("config.file");
      }
    } else {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        values.load(reader);
      } catch (IOException | RuntimeException e) {
        // RuntimeException covers a malformed unicode escape, which Properties reports unchecked.
        values.clear();
        ignored.add("config.file");
      }
    }
    for (String key : values.stringPropertyNames()) {
      if (!KNOWN.contains(key)) {
        ignored.add(key);
      }
    }
    ignored.sort(null);
    for (String key : KNOWN) {
      String override = overrides.getProperty(key);
      if (override != null) {
        values.setProperty(key, override);
      }
    }

    String url = values.getProperty(BACKEND_URL);
    if (url == null || url.isBlank()) {
      url = DEFAULT_BACKEND_URL;
    } else if (!isHttpUri(url.trim())) {
      ignored.add(BACKEND_URL);
      url = DEFAULT_BACKEND_URL;
    } else {
      url = url.trim();
    }

    Locale locale = Locale.getDefault();
    String tag = values.getProperty(LOCALE);
    if (tag != null && !tag.isBlank()) {
      Locale parsed = Locale.forLanguageTag(tag.trim());
      if (parsed.getLanguage().isEmpty()) {
        ignored.add(LOCALE);
      } else {
        locale = parsed;
      }
    }

    Path layoutFile = defaultDir().resolve("layout.json");
    String layout = values.getProperty(LAYOUT_FILE);
    if (layout != null && !layout.isBlank()) {
      Path parsed = parsePath(layout.trim());
      if (parsed == null) {
        ignored.add(LAYOUT_FILE);
      } else {
        layoutFile = parsed;
      }
    }

    return new DesktopConfig(url, locale, layoutFile, ignored);
  }

  /** The path, or null when the text is not a legal path on this platform. */
  private static Path parsePath(String text) {
    try {
      return Path.of(text);
    } catch (InvalidPathException e) {
      return null;
    }
  }

  private static boolean isHttpUri(String text) {
    try {
      URI uri = URI.create(text);
      return uri.isAbsolute()
          && uri.getHost() != null
          && ("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static Path defaultDir() {
    return Path.of(System.getProperty("user.home"), ".dynamic-form-desktop");
  }

  public String backendUrl() {
    return backendUrl;
  }

  public Locale locale() {
    return locale;
  }

  public Path layoutFile() {
    return layoutFile;
  }

  /** Keys of entries that were unknown or invalid and so not used. */
  public List<String> ignored() {
    return ignored;
  }
}
