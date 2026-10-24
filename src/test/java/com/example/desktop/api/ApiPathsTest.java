package com.example.desktop.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Guards the path builders against a form key that is not already URL-safe.
 *
 * <p>Harmless while every key matched {@code ^[a-z0-9-]+$}, but {@code FormEditorPane} lets an
 * admin type one, so a space or a {@code #} is now reachable from the UI.
 */
class ApiPathsTest {

  @Test
  void anOrdinaryKeyIsLeftAlone() {
    assertEquals("/api/forms/feedback", ApiPaths.form("feedback"));
    assertEquals("/api/form-data/feedback", ApiPaths.formData("feedback"));
    assertEquals("/api/form-data/submission/7", ApiPaths.submission(7L));
  }

  @Test
  void aSpaceIsPercentEncodedRatherThanLeftToBreakUriCreate() {
    // URI.create throws IllegalArgumentException on a raw space, so the request never even goes
    // out.
    assertEquals("/api/forms/my%20form", ApiPaths.form("my form"));
    assertDoesNotThrow(() -> URI.create("http://localhost:8080" + ApiPaths.form("my form")));
  }

  @Test
  void charactersThatWouldSilentlyTruncateThePathAreEncoded() {
    // A raw # starts a fragment and a raw ? starts a query, so the backend would receive
    // /api/forms/a and answer about a different form, or 404, with nothing in the client to say
    // why.
    assertEquals("/api/forms/a%23b", ApiPaths.form("a#b"));
    assertEquals("/api/forms/a%3Fb", ApiPaths.form("a?b"));
    assertEquals("/api/form-data/a%23b", ApiPaths.formData("a#b"));
  }

  @Test
  void aSlashCannotEscapeIntoAnotherPathSegment() {
    assertEquals("/api/forms/a%2Fb", ApiPaths.form("a/b"));
    assertEquals("/api/forms/..%2F..%2Fadmin", ApiPaths.form("../../admin"));
  }

  @Test
  void aPlusStaysAPlusInsteadOfBecomingASpace() {
    // URLEncoder is written for query strings, where a space is "+". In a path it is %20, and a
    // literal + must survive as %2B, or the two swap meanings.
    assertEquals("/api/forms/a%2Bb", ApiPaths.form("a+b"));
  }
}
