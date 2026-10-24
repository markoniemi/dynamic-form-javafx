package com.example.desktop.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

  @Test
  void theKeysTheApplicationAsksForAllExist() {
    // Messages.get returns the key when it is missing, by design — a typo must never be fatal.
    // The cost is that a missing key reaches the user as "form.submit" on a button, and nothing
    // notices. This is where it gets noticed.
    for (String key :
        List.of(
            "app.title",
            "app.titleAdmin",
            "login.failed",
            "field.unsupportedType",
            "form.submit",
            "common.error",
            "navigation.forms",
            "navigation.submissions",
            "navigation.createForm")) {
      assertNotEquals(key, Messages.get(key), key + " is missing from the bundle");
    }
  }

  @Test
  void theAdminTitleIsBuiltByTheBundleRatherThanByConcatenation() {
    assertTrue(
        Messages.format("app.titleAdmin", "Dynamic Form Desktop").contains("Dynamic Form Desktop"));
    assertNotEquals(
        "app.titleAdmin", Messages.format("app.titleAdmin", "x"), "the key must resolve");
  }

  @Test
  void theUnsupportedTypeMessageNamesTheType() {
    assertTrue(Messages.format("field.unsupportedType", "signature").contains("signature"));
  }
}
