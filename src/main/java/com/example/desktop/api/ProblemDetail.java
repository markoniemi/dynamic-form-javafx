package com.example.desktop.api;

import com.example.dynamicform.api.ValidationErrorDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * An RFC 7807 {@code application/problem+json} body, as the backend's
 * {@code @RestControllerAdvice} writes it. A validation failure adds the {@code errors} extension;
 * other statuses carry {@code detail} only.
 *
 * <p>Unknown properties are ignored here, on this class alone rather than globally, because the
 * backend also sends {@code type} and {@code instance} which the client has no use for — while a
 * typo in a DTO elsewhere should still fail loudly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProblemDetail {

  private int status;
  private String title;
  private String detail;
  private List<ValidationErrorDto> errors;

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDetail() {
    return detail;
  }

  public void setDetail(String detail) {
    this.detail = detail;
  }

  /** Field-level validation errors, or null when the body carried none. */
  public List<ValidationErrorDto> getErrors() {
    return errors;
  }

  public void setErrors(List<ValidationErrorDto> errors) {
    this.errors = errors;
  }
}
