package com.example.desktop.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Backend endpoint paths, relative to the backend's base URL.
 *
 * <p>These live here rather than in {@code dynamic-form-api}, which ships DTOs and nothing else.
 * Moving them into that jar so the backend's own annotations use the same constants is proposed in
 * the spec; if that happens, delete this class and import the jar's.
 */
public final class ApiPaths {

  /** Public. Returns a bare {@code text/plain} string, not JSON. */
  public static final String CONFIG_ISSUER_URI = "/api/config/oauth2-issuer-uri";

  public static final String FORMS = "/api/forms";
  public static final String FORM_DATA = "/api/form-data";

  private ApiPaths() {}

  /** A single form definition, e.g. {@code /api/forms/feedback}. */
  public static String form(String formKey) {
    return FORMS + "/" + segment(formKey);
  }

  /** Submissions of one form, e.g. {@code /api/form-data/feedback}. */
  public static String formData(String formKey) {
    return FORM_DATA + "/" + segment(formKey);
  }

  /**
   * Encodes one path segment.
   *
   * <p>A form key used to be safe by construction, but {@code FormEditorPane} lets an admin type
   * one, and an unencoded key either breaks the request or changes its meaning: a space throws
   * {@link IllegalArgumentException} out of {@code URI.create}, a {@code #} or {@code ?} truncates
   * the path into a fragment or a query, and a {@code /} silently addresses a different endpoint.
   *
   * <p>{@link URLEncoder} is written for {@code application/x-www-form-urlencoded}, where a space
   * becomes {@code +}; in a path it has to be {@code %20}. Rewriting {@code +} afterwards is safe
   * because a literal plus in the input has already become {@code %2B}, so the only {@code +} left
   * is one the encoder produced for a space.
   */
  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** A single submission, e.g. {@code /api/form-data/submission/7}. */
  public static String submission(long id) {
    return FORM_DATA + "/submission/" + id;
  }
}
