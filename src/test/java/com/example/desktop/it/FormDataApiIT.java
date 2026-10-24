package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.ApiException;
import com.example.desktop.api.FormDataService;
import com.example.desktop.json.JsonMapperFactory;
import com.example.dynamicform.api.FormDataDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Submission handling against the real backend. The wire-format assertions matter: the backend
 * coerces nothing, so whatever JSON type goes in is what comes back out.
 */
class FormDataApiIT {

  private static FormDataService userDataApi;
  private static FormDataService adminDataApi;

  @BeforeAll
  static void apis() {
    userDataApi = formDataApi("user", "user");
    adminDataApi = formDataApi("admin", "admin");
  }

  private static FormDataService formDataApi(String username, String password) {
    String token = TokenClient.accessToken(username, password);
    return new FormDataService(
        new ApiClient(StackFixture.backendUrl(), () -> token, JsonMapperFactory.create()));
  }

  /** The seeded contact form's fields; any valid submission will do, the backend validates none. */
  private static Map<String, Object> contactData() {
    return Map.of("name", "Tester", "email", "t@example.com", "message", "hello");
  }

  @Test
  void submitsTheReactWireFormatAndReadsItBackUnchanged() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("productName", "Widget");
    data.put("purchaseDate", "2026-01-02"); // yyyy-MM-dd string
    data.put("quality", "good");
    data.put("features", List.of("feature1", "feature3")); // multi-option checkbox: an array
    data.put("improvements", "");

    FormDataDto saved = userDataApi.submit("feedback", data); // 200, not 201

    assertEquals("feedback", saved.getFormKey());
    assertNotNull(saved.getSubmittedAt());
    assertEquals("2026-01-02", saved.getData().get("purchaseDate"));
    assertEquals(List.of("feature1", "feature3"), saved.getData().get("features"));
    assertEquals(saved.getData(), userDataApi.get(saved.getId()).getData());
  }

  @Test
  void submittingToAMissingFormGivesNotFound() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> userDataApi.submit("no-such-form", Map.of("a", "b")));

    assertEquals(404, e.getStatus());
  }

  @Test
  void userSeesOnlyOwnSubmissionsWhileAdminSeesAll() {
    FormDataDto mine = userDataApi.submit("contact", contactData());

    assertTrue(userDataApi.list().stream().allMatch(d -> d.getSubmittedBy().equals("user")));
    assertTrue(adminDataApi.list().stream().anyMatch(d -> d.getId().equals(mine.getId())));
  }

  @Test
  void onlyAdminDeletesASubmission() {
    FormDataDto mine = userDataApi.submit("contact", contactData());

    assertThrows(ApiException.class, () -> userDataApi.delete(mine.getId())); // 403
    assertDoesNotThrow(() -> adminDataApi.delete(mine.getId())); // 200, not 204
  }
}
