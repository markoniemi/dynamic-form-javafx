package com.example.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Guards the build's dependency set. Every later task needs these on the classpath, and a missing
 * one fails here with a clear name instead of further along as a compile error.
 */
class DependenciesTest {

  @Test
  void apiJarDtosAreOnTheClasspath() {
    assertDoesNotThrow(() -> load("com.example.dynamicform.api.FormDto"));
    assertDoesNotThrow(() -> load("com.example.dynamicform.api.FormDataDto"));
    assertDoesNotThrow(() -> load("com.example.dynamicform.api.ValidationErrorDto"));
  }

  @Test
  void apiJarHasNoPathOrRoleConstants() {
    // An earlier draft of the spec claimed these; they do not exist, so the client defines its own
    // in com.example.desktop.api (Task 3). This fails loudly if the api jar ever adds them, which
    // is the signal to delete the client's copies.
    assertThrows(ClassNotFoundException.class, () -> load("com.example.dynamicform.api.ApiPaths"));
  }

  @Test
  void snapFxAndJavaFxAndTestcontainersAreOnTheClasspath() {
    assertDoesNotThrow(() -> load("org.snapfx.SnapFX"));
    assertDoesNotThrow(() -> load("org.snapfx.model.DockNode"));
    assertDoesNotThrow(() -> load("com.google.gson.Gson")); // SnapFX requires it
    assertDoesNotThrow(() -> load("javafx.scene.control.TextField"));
    assertDoesNotThrow(() -> load("com.example.dynamicform.testcontainers.DynamicFormStack"));
  }

  /**
   * Resolves a class without running its static initializer. Initializing is what the one-argument
   * {@code Class.forName} does, and for a JavaFX control that fails with "Toolkit not initialized"
   * even though the jar is present — {@code Control.<clinit>} wants a running toolkit. Presence on
   * the classpath is all this test is about.
   */
  private static Class<?> load(String className) throws ClassNotFoundException {
    return Class.forName(className, false, DependenciesTest.class.getClassLoader());
  }
}
