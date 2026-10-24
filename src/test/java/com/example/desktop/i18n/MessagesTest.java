package com.example.desktop.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
  void everyKeyTheMainSourcesAskForExists() throws IOException {
    // Messages.get returns the key when it is missing, by design — a typo must never be fatal.
    // The cost is that a missing key reaches the user as "form.submit" on a button, and nothing
    // notices. So rather than listing keys by hand, which only covers the ones someone remembered,
    // this reads every key the production sources actually ask for.
    Pattern usage = Pattern.compile("Messages\\.(?:get|format)\\(\\s*\"([^\"]+)\"");
    List<String> missing = new ArrayList<>();
    List<String> found = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(Path.of("src/main/java"))) {
      for (Path file : tree.filter(path -> path.toString().endsWith(".java")).toList()) {
        Matcher match = usage.matcher(Files.readString(file));
        while (match.find()) {
          String key = match.group(1);
          found.add(key);
          if (key.equals(Messages.get(key))) {
            missing.add(file.getFileName() + " asks for " + key);
          }
        }
      }
    }

    assertEquals(List.of(), missing);
    assertTrue(found.size() > 20, "the sweep found almost nothing, so it is not working: " + found);
  }

  @Test
  void theKeysChosenAtRuntimeExistToo() {
    // The sweep above only sees a literal passed straight to get/format. These four are chosen
    // inside a conditional, so they have to be listed.
    for (String key :
        List.of("form.success", "form.updateSuccess", "form.error", "common.loading")) {
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
