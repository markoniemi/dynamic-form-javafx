package com.example.desktop.it;

import com.example.dynamicform.testcontainers.DynamicFormStack;

/**
 * The backend, auth server and Postgres, started once per test JVM and shared by every {@code *IT}.
 *
 * <p>Never start a stack per test class: the auth server binds host port <strong>9000</strong> on a
 * fixed port, because the {@code iss} claim has to equal the issuer the backend was told about, so
 * a second stack would collide. Startup also costs real time — three containers, with the backend
 * waiting on {@code /actuator/health}.
 *
 * <p>Docker must be running, and host port 9000 must be free.
 */
final class StackFixture {

  private static DynamicFormStack stack;

  private StackFixture() {}

  private static synchronized DynamicFormStack stack() {
    if (stack == null) {
      DynamicFormStack started =
          new DynamicFormStack()
              .withAuthConfigFile("oauth2-server.yaml")
              .withLogConsumer(frame -> System.out.print(frame.getUtf8String()))
              .start();
      Runtime.getRuntime().addShutdownHook(new Thread(started::close));
      stack = started;
    }
    return stack;
  }

  static String backendUrl() {
    return stack().backendUrl();
  }

  /** Always {@code http://localhost:9000} — the issuer the tokens carry. */
  static String authUrl() {
    return stack().authUrl();
  }
}
