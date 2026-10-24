package com.example.desktop.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.testfx.api.FxToolkit;

/**
 * Base class for every test that touches JavaFX.
 *
 * <p>Starts the toolkit once per JVM — {@link Platform#startup} may only be called once, and a
 * JavaFX control's static initialiser needs a running toolkit, which is why a plain unit test
 * cannot simply construct one. Runs headless under Monocle; the system properties that select it
 * are set by surefire and failsafe, not expected on the command line.
 */
public abstract class UiTest {

  private static final long TIMEOUT_SECONDS = 10;

  private static Stage primaryStage;

  static {
    try {
      FxToolkit.registerPrimaryStage();
      primaryStage = FxToolkit.toolkitContext().getRegisteredStage();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not start the JavaFX toolkit headlessly. Check that openjfx-monocle is on the "
              + "test classpath and that testfx.headless/glass.platform/monocle.platform reached "
              + "the JVM.",
          e);
    }
  }

  /**
   * A stage tests can build a scene into. Shared, so a test that uses it should not assume it is
   * empty.
   */
  protected static Stage stage() {
    return primaryStage;
  }

  /** Runs the block on the FX thread and waits for it. */
  protected static void onFx(Runnable block) {
    onFx(
        () -> {
          block.run();
          return null;
        });
  }

  /** Runs the block on the FX thread, waits, and returns its result. */
  protected static <T> T onFx(Supplier<T> block) {
    if (Platform.isFxApplicationThread()) {
      return block.get();
    }
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    Platform.runLater(
        () -> {
          try {
            result.set(block.get());
          } catch (RuntimeException e) {
            failure.set(e);
          } finally {
            done.countDown();
          }
        });
    await(done, "the FX thread did not finish the block");
    if (failure.get() != null) {
      throw failure.get();
    }
    return result.get();
  }

  /** Reads control state on the FX thread. */
  protected static <T> T fxValue(Supplier<T> read) {
    return onFx(read);
  }

  /** Polls until the condition holds, failing rather than hanging if it never does. */
  protected static void waitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep();
    }
    throw new AssertionError("Condition did not become true within " + TIMEOUT_SECONDS + "s");
  }

  private static void await(CountDownLatch latch, String message) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError(message);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted waiting for the FX thread", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(25);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while polling", e);
    }
  }
}
