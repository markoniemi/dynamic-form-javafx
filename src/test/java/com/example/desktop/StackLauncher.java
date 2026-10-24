package com.example.desktop;

import com.example.dynamicform.testcontainers.DynamicFormStack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Holds the backend stack in Docker and publishes its URL, so the application can be restarted
 * against it without paying the container startup each time.
 *
 * <p>The backend's host port is random and {@code DynamicFormStack} has no reuse, so a second
 * process cannot guess the URL. It is written to {@code target/dev-stack.properties}, in the shape
 * {@code DesktopConfig} already reads.
 *
 * <pre>
 * mvn -q test-compile exec:java@stack
 * mvn -q test-compile exec:java@app -Dconfig.file=target/dev-stack.properties
 * </pre>
 *
 * <p>The stack lives as long as this process; {@code Ctrl+C} stops it. Docker must be running and
 * host port 9000 must be free.
 */
public final class StackLauncher {

  static final Path DEFAULT_FILE = Path.of("target", "dev-stack.properties");

  private StackLauncher() {}

  /**
   * Writes a properties file holding only {@code backend.url}, replacing any previous content, so a
   * URL from an earlier run cannot survive.
   */
  static Path writeProperties(Path file, String backendUrl) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return Files.writeString(file, "backend.url=" + backendUrl + System.lineSeparator());
  }

  public static void main(String[] args) throws Exception {
    DynamicFormStack stack =
        new DynamicFormStack()
            .withAuthConfigFile("oauth2-server.yaml")
            .withLogConsumer(frame -> System.out.print(frame.getUtf8String()))
            .start();
    Runtime.getRuntime().addShutdownHook(new Thread(stack::close));
    Path file = writeProperties(DEFAULT_FILE, stack.backendUrl());
    System.out.println("Backend: " + stack.backendUrl());
    System.out.println("Auth:    " + stack.authUrl());
    System.out.println("Run the app against it from another terminal:");
    System.out.println(
        "mvn -q test-compile exec:java@app -Dconfig.file="
            + file.toString().replace(java.io.File.separatorChar, '/'));
    Thread.currentThread().join();
  }

  /** Unchecked variant for callers that cannot handle the exception. */
  static Path writeDefault(String backendUrl) {
    try {
      return writeProperties(DEFAULT_FILE, backendUrl);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
