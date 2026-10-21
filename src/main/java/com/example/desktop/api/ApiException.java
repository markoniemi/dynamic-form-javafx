package com.example.desktop.api;

import com.example.dynamicform.api.ValidationErrorDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * A non-2xx response from the backend.
 *
 * <p>Carries the HTTP status plus whatever the RFC 7807 body held. {@link #getErrors()} is never
 * null, so a caller wiring validation messages onto controls can iterate it unconditionally.
 */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String detail;
  private final List<ValidationErrorDto> errors;

  private ApiException(int status, String detail, List<ValidationErrorDto> errors) {
    super(status + " " + detail);
    this.status = status;
    this.detail = detail;
    this.errors = errors;
  }

  /**
   * Builds an exception from a response the client could not use.
   *
   * <p>The body is best-effort: a problem document gives a {@code detail} and possibly field
   * errors, but not every failure produces one. A 401 comes from the resource-server filter rather
   * than the exception advice and has an <em>empty</em> body, and a proxy or gateway can return
   * HTML, so anything unparseable falls back to a status-derived message. This method never throws.
   */
  public static ApiException from(int status, String body, ObjectMapper mapper) {
    if (body != null && !body.isBlank()) {
      try {
        ProblemDetail problem = mapper.readValue(body, ProblemDetail.class);
        if (problem.getDetail() != null && !problem.getDetail().isBlank()) {
          List<ValidationErrorDto> errors =
              problem.getErrors() == null ? List.of() : List.copyOf(problem.getErrors());
          return new ApiException(status, problem.getDetail(), errors);
        }
      } catch (Exception e) {
        // not a problem document; fall through to the body or the status
      }
      return new ApiException(status, body.strip(), List.of());
    }
    return new ApiException(status, reasonFor(status), List.of());
  }

  /** A readable message for a response with no body to quote. */
  private static String reasonFor(int status) {
    return switch (status) {
      case 401 -> "Unauthorized: not logged in, or the access token has expired";
      case 403 -> "Forbidden";
      case 404 -> "Not found";
      default -> "Request failed with status " + status;
    };
  }

  public int getStatus() {
    return status;
  }

  public String getDetail() {
    return detail;
  }

  /** Field-level validation errors; empty when the response carried none. */
  public List<ValidationErrorDto> getErrors() {
    return errors;
  }
}
