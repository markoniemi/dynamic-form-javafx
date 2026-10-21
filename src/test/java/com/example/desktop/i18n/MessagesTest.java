package com.example.desktop.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The bundle mirrors the React frontend's key paths so a translation can be lifted from either
 * project. i18next's {{name}} placeholders become MessageFormat's positional {0}.
 */
class MessagesTest {

  @Test
  void resolvesAKeyMirroringTheFrontend() {
    assertEquals("Submit", Messages.get("form.submit"));
  }

  @Test
  void interpolatesArguments() {
    assertEquals("Email is required", Messages.format("form.required", "Email"));
  }

  @Test
  void anUnknownKeyReturnsTheKeyRatherThanThrowing() {
    assertEquals("no.such.key", Messages.get("no.such.key"));
  }
}
