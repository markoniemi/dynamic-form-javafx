package com.example.desktop;

/**
 * Starts the application from a class that does not extend {@code Application}, for IDE run
 * configurations.
 *
 * <p>The {@code java} launcher refuses a main class that extends {@code Application} unless the
 * JavaFX modules are on the module path — "Error: JavaFX runtime components are missing, and are
 * required to run this application". This project is non-modular and carries JavaFX as ordinary
 * classpath jars, so a run configuration pointed straight at {@link DesktopApp} always hits that
 * check.
 *
 * <p>The check inspects only the class named on the command line, and {@code Application.launch}
 * itself works from the classpath, so going through here is enough; no module path and no {@code
 * --add-modules} are needed. Maven's exec plugin never had the problem because it invokes {@code
 * main} reflectively inside a running JVM.
 */
public final class DesktopLauncher {

  private DesktopLauncher() {}

  public static void main(String[] args) {
    DesktopApp.main(args);
  }
}
