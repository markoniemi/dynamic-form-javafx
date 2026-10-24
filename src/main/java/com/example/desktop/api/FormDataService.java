package com.example.desktop.api;

import com.example.dynamicform.api.FormDataDto;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;

/**
 * Form submissions. Takes and returns the published DTOs only; no caching, no retry, no business
 * logic.
 *
 * <p>Writes take a bare value map rather than a {@link FormDataDto}, because that is what the
 * endpoints accept. The backend stores the map as JSON without inspecting the form's field types,
 * so each value's JSON type is the caller's responsibility: a date is a {@code "yyyy-MM-dd"}
 * string, a number is a string, and a multi-option checkbox is a list of the selected values.
 */
public class FormDataService {

  public static final String FORM_DATA = "/api/form-data";

  /** Submissions of one form, e.g. {@code /api/form-data/feedback}. */
  public static String path(String formKey) {
    return FORM_DATA + "/" + ApiClient.pathSegment(formKey);
  }

  /** A single submission, e.g. {@code /api/form-data/submission/7}. */
  public static String submissionPath(long id) {
    return FORM_DATA + "/submission/" + id;
  }

  private static final TypeReference<List<FormDataDto>> SUBMISSIONS = new TypeReference<>() {};

  private final ApiClient client;

  public FormDataService(ApiClient client) {
    this.client = client;
  }

  public FormDataDto submit(String formKey, Map<String, Object> data) {
    return client.post(path(formKey), data, FormDataDto.class);
  }

  /**
   * Submissions visible to the caller: their own for a plain user, all of them for an admin. The
   * backend offers no filtering and no paging, so narrowing by form key happens here.
   */
  public List<FormDataDto> list() {
    return client.get(FORM_DATA, SUBMISSIONS);
  }

  public FormDataDto get(long id) {
    return client.get(submissionPath(id), FormDataDto.class);
  }

  /** Updates a submission. Owner-only: an admin editing someone else's gets a 403. */
  public FormDataDto update(long id, Map<String, Object> data) {
    return client.put(submissionPath(id), data, FormDataDto.class);
  }

  /** Deletes a submission. Needs {@code ROLE_ADMIN}. */
  public void delete(long id) {
    client.delete(submissionPath(id));
  }
}
