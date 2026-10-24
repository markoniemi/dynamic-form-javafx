package com.example.desktop.it;

import com.example.dynamicform.testcontainers.DynamicFormStack;
import java.util.function.Supplier;

/**
 * The backend, auth server and Postgres, started once per test JVM and shared by every {@code *IT}.
 *
 * <p>Never start a stack per test class: the auth server binds host port <strong>9000</strong> on a
 * fixed port, because the {@code iss} claim has to equal the issuer the backend was told about, so
 * a second stack would collide. Startup also costs real time — three containers, with the backend
 * waiting on {@code /actuator/health}.
 *
 * <p>Docker must be running, and host port 9000 must be free. A start that fails is remembered:
 * with seven {@code *IT} classes, retrying meant seven three-container starts and seven 2-minute
 * health waits before each was told the same thing.
 */
final class StackFixture {

  private static DynamicFormStack stack;
  private static RuntimeException failure;

  private StackFixture() {}

  private static DynamicFormStack stack() {
    return stack(StackFixture::startStack);
  }

  /**
   * The seam the fixture's own test uses: it passes a start action that fails, so the caching can
   * be proven without Docker.
   */
  static synchronized DynamicFormStack stack(Supplier<DynamicFormStack> starter) {
    if (failure != null) {
      throw new IllegalStateException(
          "The test stack failed to start earlier in this JVM; not retrying", failure);
    }
    if (stack == null) {
      try {
        DynamicFormStack started = starter.get();
        Runtime.getRuntime().addShutdownHook(new Thread(started::close));
        stack = started;
      } catch (RuntimeException e) {
        failure = e;
        throw e;
      }
    }
    return stack;
  }

  /**
   * Starts the real stack, holding the backend's log until it is known whether it is wanted.
   *
   * <p>The consumer has to be attached before {@code start()} or the startup log is already gone by
   * the time it would be useful, so the log is buffered and printed only if the start fails. After
   * a successful start it is dropped rather than buffered, which would otherwise grow for the whole
   * test run.
   */
  private static DynamicFormStack startStack() {
    StringBuilder startupLog = new StringBuilder();
    boolean[] started = {false};
    try {
      DynamicFormStack stackUnderStart =
          new DynamicFormStack()
              .withAuthConfigFile("oauth2-server.yaml")
              .withLogConsumer(
                  frame -> {
                    if (!started[0]) {
                      startupLog.append(frame.getUtf8String());
                    }
                  })
              .start();
      started[0] = true;
      return stackUnderStart;
    } catch (RuntimeException e) {
      // The cause is usually in the backend's own output rather than in the Java stack trace.
      System.err.println("The test stack failed to start. Backend log follows:");
      System.err.println(startupLog);
      throw e;
    }
  }

  /** Lets {@link StackFixtureTest} run more than one case against the fixture's static state. */
  static synchronized void resetForTest() {
    stack = null;
    failure = null;
  }

  static String backendUrl() {
    return stack().backendUrl();
  }

  /** Always {@code http://localhost:9000} — the issuer the tokens carry. */
  static String authUrl() {
    return stack().authUrl();
  }
}
