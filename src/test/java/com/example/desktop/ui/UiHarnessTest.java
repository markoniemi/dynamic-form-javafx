package com.example.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link UiTest} itself, so a toolkit that will not start headlessly fails here with a
 * clear message rather than in every pane test.
 *
 * <p>A top-level class rather than one nested in {@code UiTest}: surefire skips the abstract base
 * and never scans inside it, so a nested test would silently not run.
 */
class UiHarnessTest extends UiTest {

  @Test
  void runsBlocksOnTheFxThreadAndReturnsTheirValues() {
    Label label = onFx(() -> new Label("hello"));

    assertEquals("hello", fxValue(label::getText));
    assertTrue(onFx(Platform::isFxApplicationThread), "the block must run on the FX thread");
  }

  @Test
  void waitUntilReturnsOnceTheConditionHolds() {
    long start = System.nanoTime();

    waitUntil(() -> System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(50));
  }

  @Test
  void aJavaFxControlCanBeConstructed() {
    // The foundation could not do this: Control's static initialiser needs a running toolkit.
    assertEquals("Forms", fxValue(() -> new Label("Forms").getText()));
  }
}
