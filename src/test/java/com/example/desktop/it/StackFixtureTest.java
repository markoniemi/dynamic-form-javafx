package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.dynamicform.testcontainers.DynamicFormStack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * No Docker needed: the fixture is handed a start action that fails, and what is under test is that
 * the second caller is not made to wait for the same failure again.
 *
 * <p>A {@code *Test}, so it runs under surefire in its own JVM, where nothing else touches the
 * fixture's static state.
 */
class StackFixtureTest {

  @AfterEach
  void clearFixtureState() {
    StackFixture.resetForTest();
  }

  @Test
  void aStartThatFailedOnceIsNotRetried() {
    // Seven IT classes meeting a stopped Docker daemon, or a busy port 9000, used to mean seven
    // three-container starts and seven 2-minute health waits to be told the same thing.
    AtomicInteger starts = new AtomicInteger();
    Supplier<DynamicFormStack> boom =
        () -> {
          starts.incrementAndGet();
          throw new IllegalStateException("Could not find a valid Docker environment");
        };

    Exception first = assertThrows(Exception.class, () -> StackFixture.stack(boom));
    Exception second = assertThrows(Exception.class, () -> StackFixture.stack(boom));

    assertEquals(1, starts.get(), "the second caller must get the cached failure");
    assertSame(first, second.getCause(), "and must be told why, with the original cause");
    assertTrue(second.getMessage().contains("not retrying"), second.getMessage());
  }
}
