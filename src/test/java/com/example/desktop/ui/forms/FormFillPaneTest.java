package com.example.desktop.ui.forms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.field.FieldControl;
import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FieldOptionDto;
import com.example.dynamicform.api.FormDto;
import com.example.dynamicform.api.ValidationErrorDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code collect()} is the seam that makes the wire format testable without a backend: these pin
 * what would actually be sent, per field type.
 */
class FormFillPaneTest extends UiTest {

  @Test
  void collectBuildsTheReactWireFormatForEveryFieldType() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(allTypesForm()));

    onFx(
        () ->
            pane.setValues(
                Map.of(
                    "aText", "x",
                    "aNumber", "42",
                    "aDate", "2026-01-02",
                    "aSelect", "s1",
                    "aRadio", "r1",
                    "aChecks", List.of("c1", "c3"))));

    Map<String, Object> data = fxValue(pane::collect);

    assertEquals("x", data.get("aText"));
    assertEquals("42", data.get("aNumber")); // a string, not a JSON number
    assertEquals("2026-01-02", data.get("aDate"));
    assertEquals("s1", data.get("aSelect"));
    assertEquals("r1", data.get("aRadio"));
    assertEquals(List.of("c1", "c3"), data.get("aChecks"));
  }

  @Test
  void collectOmitsAnUnknownFieldTypeRatherThanSendingAnEmptyValue() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(field("mystery", "signature"), field("aText", "text"))));

    Map<String, Object> data = fxValue(pane::collect);

    assertFalse(data.containsKey("mystery"));
    assertTrue(data.containsKey("aText"));
  }

  @Test
  void aRequiredFieldLeftBlankIsRejectedBeforeAnyRequestIsMade() {
    // The api is null: if submit() reached it, this would throw rather than fail an assertion.
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(requiredField("name", "text"))));

    onFx(pane::submit);

    assertTrue(
        fxValue(
            () ->
                pane.controlFor("name")
                    .node()
                    .getStyleClass()
                    .contains(FieldControl.ERROR_STYLE_CLASS)));
  }

  @Test
  void anInvalidEmailIsRejectedBeforeAnyRequestIsMade() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(requiredField("email", "email"))));

    onFx(() -> pane.setValues(Map.of("email", "not-an-email")));
    onFx(pane::submit);

    assertTrue(
        fxValue(
            () ->
                pane.controlFor("email")
                    .node()
                    .getStyleClass()
                    .contains(FieldControl.ERROR_STYLE_CLASS)));
  }

  @Test
  void aClientSideRejectionAlsoSaysSomethingInTheBanner() {
    // Marking the control is not enough on its own: the style class needs a stylesheet to show,
    // and a tooltip needs hovering. Without a banner line the Submit button looks simply dead —
    // and since the backend validates no submission data, this is the only validation there is.
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(requiredField("name", "text"))));

    onFx(pane::submit);

    assertFalse(fxValue(pane::bannerText).isBlank(), "a rejected submit must say why");
  }

  @Test
  void correctingAFieldClearsItsMessageAsWellAsItsMark() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(requiredField("name", "text"))));
    onFx(pane::submit);

    onFx(() -> pane.setValues(Map.of("name", "filled")));
    onFx(pane::submit);

    assertTrue(
        fxValue(() -> pane.controlFor("name").node().getStyleClass()).stream()
            .noneMatch(FieldControl.ERROR_STYLE_CLASS::equals),
        "the mark must not survive a correction");
    assertNull(
        fxValue(() -> pane.controlFor("name").node().getProperties().get("javafx.scene.control.Tooltip")),
        "nor may a stale tooltip");
  }

  @Test
  void anUnknownFieldTypeDoesNotBlockSubmitOrBlankThePane() {
    // A null type is the shape a non-conforming server produces; it must degrade, not throw.
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));

    assertDoesNotThrow(
        () ->
            onFx(
                () ->
                    pane.show(
                        formWith(
                            FieldDto.builder().name("odd").label("Odd").type(null).build(),
                            field("aText", "text")))));

    assertNotNull(fxValue(() -> pane.controlFor("aText")), "the rest of the form must still render");
    assertFalse(fxValue(pane::collect).containsKey("odd"));
  }

  @Test
  void serverValidationErrorsAreShownAgainstTheNamedControls() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(field("email", "email"))));

    onFx(
        () ->
            pane.showErrors(
                List.of(new ValidationErrorDto("email", "Must be an email", "Email"))));

    assertTrue(
        fxValue(
            () ->
                pane.controlFor("email")
                    .node()
                    .getStyleClass()
                    .contains(FieldControl.ERROR_STYLE_CLASS)));
  }

  @Test
  void aValidationErrorWithNoMatchingControlIsShownInTheBanner() {
    // The backend sends class-level errors with a null field; dropping them would leave the user
    // staring at a form that was rejected for no visible reason.
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(field("email", "email"))));

    onFx(
        () ->
            pane.showErrors(
                List.of(
                    new ValidationErrorDto(null, "Form is not accepting submissions", "Closed"),
                    new ValidationErrorDto("notAField", "Stale field", "Unknown"))));

    String banner = fxValue(pane::bannerText);
    assertTrue(banner.contains("not accepting submissions"), banner);
    assertTrue(banner.contains("Stale field"), banner);
  }

  private static FormDto allTypesForm() {
    return formWith(
        field("aText", "text"),
        field("aNumber", "number"),
        field("aDate", "date"),
        option("aSelect", "select", "s1", "s2"),
        option("aRadio", "radio", "r1", "r2"),
        option("aChecks", "checkbox", "c1", "c2", "c3"));
  }

  private static FormDto formWith(FieldDto... fields) {
    return FormDto.builder().formKey("t").title("T").fields(List.of(fields)).build();
  }

  private static FieldDto field(String name, String type) {
    return FieldDto.builder().name(name).label(name).type(type).build();
  }

  private static FieldDto requiredField(String name, String type) {
    return FieldDto.builder().name(name).label(name).type(type).required(true).build();
  }

  private static FieldDto option(String name, String type, String... values) {
    return FieldDto.builder()
        .name(name)
        .label(name)
        .type(type)
        .options(List.of(values).stream().map(v -> new FieldOptionDto(v, v)).toList())
        .build();
  }
}
