package com.example.desktop.api;

import com.example.dynamicform.api.FormDto;
import com.example.dynamicform.api.FormListItemDto;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/**
 * Form definitions. Takes and returns the published DTOs only; no caching, no retry, no business
 * logic. Writes need {@code ROLE_ADMIN} and fail with {@link ApiException} 403 otherwise.
 */
public class FormService {

  public static final String FORMS = "/api/forms";

  /** A single form definition, e.g. {@code /api/forms/feedback}. */
  public static String path(String formKey) {
    return FORMS + "/" + ApiClient.pathSegment(formKey);
  }

  private static final TypeReference<List<FormListItemDto>> LIST_ITEMS = new TypeReference<>() {};

  private final ApiClient client;

  public FormService(ApiClient client) {
    this.client = client;
  }

  /**
   * All forms, as key and title only. The backend applies no ordering, so neither does this — sort
   * at the point of display if an order is wanted.
   */
  public List<FormListItemDto> list() {
    return client.get(FORMS, LIST_ITEMS);
  }

  public FormDto get(String formKey) {
    return client.get(path(formKey), FormDto.class);
  }

  public FormDto create(FormDto form) {
    return client.post(FORMS, form, FormDto.class);
  }

  public FormDto update(String formKey, FormDto form) {
    return client.put(path(formKey), form, FormDto.class);
  }

  public void delete(String formKey) {
    client.delete(path(formKey));
  }
}
