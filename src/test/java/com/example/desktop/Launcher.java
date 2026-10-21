package com.example.desktop;

import com.example.dynamicform.testcontainers.DynamicFormStack;

/**
 * Runs the application against a real backend for manual work — the analogue of dynamic-form's
 * {@code DevelopmentApplication}. Starts the stack in Docker, points the app at it, and hands over.
 *
 * <p>Lives under {@code src/test} because the Testcontainers dependency is test-scoped.
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.mainClass=com.example.desktop.Launcher \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Docker must be running and host port 9000 must be free. Logging in as {@code admin}/{@code
 * admin} shows the editor pane; {@code user}/{@code user} does not.
 */
public final class Launcher {

  private Launcher() {}

  public static void main(String[] args) {
    try (DynamicFormStack stack =
        new DynamicFormStack()
            .withAuthConfigFile("oauth2-server.yaml")
            .withLogConsumer(frame -> System.out.print(frame.getUtf8String()))
            .start()) {
      System.out.println("Backend: " + stack.backendUrl());
      System.out.println("Auth:    " + stack.authUrl());
      System.setProperty("backend.url", stack.backendUrl());
      App.main(args);
    }
  }
}
