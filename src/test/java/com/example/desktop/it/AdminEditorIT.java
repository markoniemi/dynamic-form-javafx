package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormApi;
import com.example.desktop.api.FormDataApi;
import com.example.desktop.auth.AuthSession;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.forms.FormEditorPane;
import com.example.desktop.ui.forms.FormFillPane;
import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FieldOptionDto;
import com.example.dynamicform.api.FormDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Admin form CRUD, including the only route to a form with all nine field types. */
class AdminEditorIT extends UiTest {

  private static final String ALL_TYPES_KEY = "it-all-types";

  @AfterEach
  void removeTestForms() {
    FormApi admin = formApi("admin", "admin");
    for (String key : List.of(ALL_TYPES_KEY, "it-denied", "it-doomed")) {
      try {
        admin.delete(key);
      } catch (RuntimeException e) {
        // not created by this test, or already gone
      }
    }
  }

  @Test
  void anAdminCreatesAFormWithEveryFieldTypeAndItComesBack() {
    // No seeded form has a number field, so the only way to cover all nine types is to make one.
    FormApi api = formApi("admin", "admin");
    FormEditorPane editor = onFx(() -> new FormEditorPane(api, state("admin", "admin")));

    onFx(
        () -> {
          editor.editNew();
          editor.setDefinition(allNineTypesForm());
          editor.save();
        });

    waitUntil(() -> api.list().stream().anyMatch(f -> ALL_TYPES_KEY.equals(f.getFormKey())));
    FormDto saved = api.get(ALL_TYPES_KEY);
    assertEquals(
        List.of("text", "email", "tel", "number", "date", "textarea", "select", "radio", "checkbox"),
        saved.getFields().stream().map(FieldDto::getType).toList());
  }

  @Test
  void serverValidationOnABadFormKeyIsShownAgainstTheKeyField() {
    FormEditorPane editor =
        onFx(() -> new FormEditorPane(formApi("admin", "admin"), state("admin", "admin")));

    onFx(
        () -> {
          editor.editNew();
          editor.setDefinition(formWithKey("Not A Key"));
          editor.save();
        });

    waitUntil(() -> fxValue(editor::errorText).toLowerCase().contains("form key"));
  }

  @Test
  void aPlainUserCannotSaveAndIsToldWhy() {
    FormEditorPane editor =
        onFx(() -> new FormEditorPane(formApi("user", "user"), state("user", "user")));

    onFx(
        () -> {
          editor.editNew();
          editor.setDefinition(formWithKey("it-denied"));
          editor.save();
        });

    waitUntil(() -> !fxValue(editor::errorText).isBlank());
    assertFalse(
        formApi("admin", "admin").list().stream()
            .anyMatch(f -> "it-denied".equals(f.getFormKey())),
        "a rejected save must not have created the form");
  }

  @Test
  void deletingAFormThatAFillPaneIsOpenOnFailsReadablyRatherThanUnhandled() {
    FormApi adminApi = formApi("admin", "admin");
    adminApi.create(formWithKey("it-doomed"));
    FormFillPane fill =
        onFx(() -> new FormFillPane(dataApi("user", "user"), state("user", "user")));
    onFx(() -> fill.show(adminApi.get("it-doomed")));

    adminApi.delete("it-doomed");
    onFx(() -> fill.setValues(Map.of("a", "x")));
    onFx(fill::submit);

    waitUntil(() -> !fxValue(fill::bannerText).isBlank());
    assertTrue(
        fxValue(fill::bannerText).toLowerCase().contains("not found"), fxValue(fill::bannerText));
  }

  private static FormDto allNineTypesForm() {
    return FormDto.builder()
        .formKey(ALL_TYPES_KEY)
        .title("All Types")
        .fields(
            List.of(
                field("aText", "text"),
                field("aEmail", "email"),
                field("aTel", "tel"),
                field("aNumber", "number"),
                field("aDate", "date"),
                field("aTextarea", "textarea"),
                option("aSelect", "select", "s1", "s2"),
                option("aRadio", "radio", "r1", "r2"),
                option("aChecks", "checkbox", "c1", "c2")))
        .build();
  }

  private static FormDto formWithKey(String formKey) {
    return FormDto.builder()
        .formKey(formKey)
        .title("T")
        .fields(List.of(field("a", "text")))
        .build();
  }

  private static FieldDto field(String name, String type) {
    return FieldDto.builder().name(name).label(name).type(type).build();
  }

  private static FieldDto option(String name, String type, String... values) {
    return FieldDto.builder()
        .name(name)
        .label(name)
        .type(type)
        .options(List.of(values).stream().map(v -> new FieldOptionDto(v, v)).toList())
        .build();
  }

  private static AppState state(String username, String password) {
    AppState state = new AppState();
    state
        .session()
        .set(
            AuthSession.fromToken(
                TokenClient.accessToken(username, password), JsonMapperFactory.create()));
    return state;
  }

  private static FormApi formApi(String username, String password) {
    return new FormApi(client(username, password));
  }

  private static FormDataApi dataApi(String username, String password) {
    return new FormDataApi(client(username, password));
  }

  private static ApiClient client(String username, String password) {
    String token = TokenClient.accessToken(username, password);
    return new ApiClient(StackFixture.backendUrl(), () -> token, JsonMapperFactory.create());
  }
}
