package com.example.desktop.api;

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
    return FORMS + "/" + formKey;
  }

  /** Submissions of one form, e.g. {@code /api/form-data/feedback}. */
  public static String formData(String formKey) {
    return FORM_DATA + "/" + formKey;
  }

  /** A single submission, e.g. {@code /api/form-data/submission/7}. */
  public static String submission(long id) {
    return FORM_DATA + "/submission/" + id;
  }
}
