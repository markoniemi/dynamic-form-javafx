package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.ApiException;
import com.example.desktop.api.FormService;
import com.example.desktop.json.JsonMapperFactory;
import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FormDto;
import com.example.dynamicform.api.FormListItemDto;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Form CRUD against the real backend, for both an admin and a plain user. */
class FormApiIT {

  private static FormService adminApi;
  private static FormService userApi;

  @BeforeAll
  static void apis() {
    adminApi = formApi("admin", "admin");
    userApi = formApi("user", "user");
  }

  private static FormService formApi(String username, String password) {
    String token = TokenClient.accessToken(username, password);
    return new FormService(
        new ApiClient(StackFixture.backendUrl(), () -> token, JsonMapperFactory.create()));
  }

  @Test
  void listsTheSeededForms() {
    List<String> keys = adminApi.list().stream().map(FormListItemDto::getFormKey).toList();

    assertTrue(keys.containsAll(List.of("contact", "feedback", "survey")), keys.toString());
  }

  @Test
  void readsTheFeedbackFormWithItsDateAndCheckboxFields() {
    FormDto form = userApi.get("feedback");

    assertEquals("Product Feedback Form", form.getTitle());
    FieldDto features =
        form.getFields().stream()
            .filter(f -> f.getName().equals("features"))
            .findFirst()
            .orElseThrow();
    assertEquals("checkbox", features.getType());
    assertEquals(4, features.getOptions().size());
    assertTrue(
        form.getFields().stream()
            .anyMatch(f -> f.getName().equals("purchaseDate") && f.getType().equals("date")));
  }

  @Test
  void adminCreatesAndDeletesAFormAndUserCannot() {
    FormDto form =
        FormDto.builder()
            .formKey("it-form")
            .title("IT")
            .fields(List.of(FieldDto.builder().name("a").label("A").type("text").build()))
            .build();

    assertEquals("it-form", adminApi.create(form).getFormKey()); // 201
    assertThrows(ApiException.class, () -> userApi.create(form)); // 403 for ROLE_USER
    assertDoesNotThrow(() -> adminApi.delete("it-form")); // 204
  }

  @Test
  void invalidFormGivesFieldLevelValidationErrors() {
    FormDto invalid = FormDto.builder().formKey("Not A Key").title("").fields(List.of()).build();

    ApiException e = assertThrows(ApiException.class, () -> adminApi.create(invalid));

    assertEquals(400, e.getStatus());
    assertEquals("Validation failed", e.getDetail());
    assertTrue(e.getErrors().stream().anyMatch(v -> v.getField().equals("formKey")));
  }

  @Test
  void missingFormGivesNotFound() {
    ApiException e = assertThrows(ApiException.class, () -> adminApi.get("no-such-form"));

    assertEquals(404, e.getStatus());
  }
}
