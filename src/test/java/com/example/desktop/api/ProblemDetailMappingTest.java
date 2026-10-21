package com.example.desktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.json.JsonMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The bodies here are the exact shapes the backend's {@code GlobalExceptionHandler} produces. */
class ProblemDetailMappingTest {

  private final ObjectMapper mapper = JsonMapperFactory.create();

  @Test
  void validationFailureExposesFieldErrors() {
    String body =
        """
        {"type":"about:blank","title":"Bad Request","status":400,"detail":"Validation failed",
         "instance":"/api/forms",
         "errors":[{"field":"formKey","message":"Form key is required","code":"NotBlank"}]}""";

    ApiException e = ApiException.from(400, body, mapper);

    assertEquals(400, e.getStatus());
    assertEquals("Validation failed", e.getDetail());
    assertEquals(1, e.getErrors().size());
    assertEquals("formKey", e.getErrors().get(0).getField());
    assertEquals("NotBlank", e.getErrors().get(0).getCode());
  }

  @Test
  void notFoundHasDetailAndNoErrors() {
    ApiException e =
        ApiException.from(404, "{\"status\":404,\"detail\":\"Form not found: missing\"}", mapper);

    assertEquals("Form not found: missing", e.getDetail());
    assertTrue(e.getErrors().isEmpty());
  }

  @Test
  void forbiddenHasDetailAndNoErrors() {
    ApiException e = ApiException.from(403, "{\"status\":403,\"detail\":\"Access Denied\"}", mapper);

    assertEquals(403, e.getStatus());
    assertEquals("Access Denied", e.getDetail());
    assertTrue(e.getErrors().isEmpty());
  }

  @Test
  void pathHelpersMatchTheBackendRoutes() {
    assertEquals("/api/forms/feedback", ApiPaths.form("feedback"));
    assertEquals("/api/form-data/feedback", ApiPaths.formData("feedback"));
    assertEquals("/api/form-data/submission/7", ApiPaths.submission(7L));
  }
}
