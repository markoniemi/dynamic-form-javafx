package com.example.desktop.ui.field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.ui.UiTest;
import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FieldOptionDto;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

/**
 * The wire format lives here. The backend coerces nothing, so whatever type a control reports is
 * what gets stored — these assertions are the contract, not a preference.
 */
class FieldControlFactoryTest extends UiTest {

  @Test
  void textEmailAndTelAllRenderATextFieldReturningAString() {
    for (String type : List.of("text", "email", "tel")) {
      FieldControl control = onFx(() -> FieldControlFactory.create(field("a", type)));

      assertInstanceOf(TextField.class, control.node(), type);
      onFx(() -> control.setValue("hello"));
      assertEquals("hello", fxValue(control::value), type);
    }
  }

  @Test
  void textareaRendersATextArea() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("notes", "textarea")));

    assertInstanceOf(TextArea.class, control.node());
  }

  @Test
  void numberIsSentAsAStringToMatchTheReactClient() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("rating", "number")));

    onFx(() -> control.setValue("42"));

    assertEquals("42", fxValue(control::value));
  }

  @Test
  void numberRejectsNonNumericTyping() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("rating", "number")));

    onFx(() -> ((TextField) control.node()).setText("12ab3"));

    assertEquals("123", fxValue(control::value), "the formatter must drop non-digits");
  }

  @Test
  void dateUsesIsoAndAnEmptyPickerIsAnEmptyString() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("purchaseDate", "date")));

    assertEquals("", fxValue(control::value), "a blank date is \"\", as the React client sends");
    onFx(() -> control.setValue("2026-01-02"));
    assertEquals("2026-01-02", fxValue(control::value));
    assertEquals(LocalDate.of(2026, 1, 2), fxValue(() -> ((DatePicker) control.node()).getValue()));
  }

  @Test
  void showErrorMarksTheControlAndClearErrorUndoesIt() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("a", "text")));

    onFx(() -> control.showError("Required"));
    assertTrue(fxValue(() -> control.node().getStyleClass().contains(FieldControl.ERROR_STYLE_CLASS)));

    onFx(control::clearError);
    assertFalse(
        fxValue(() -> control.node().getStyleClass().contains(FieldControl.ERROR_STYLE_CLASS)));
  }

  @Test
  void aCheckboxWithOptionsCollectsTheSelectedValuesAsAList() {
    FieldControl control =
        onFx(
            () ->
                FieldControlFactory.create(optionField("features", "checkbox", "f1", "f2", "f3")));

    onFx(() -> control.setValue(List.of("f1", "f3")));

    assertEquals(List.of("f1", "f3"), fxValue(control::value));
  }

  @Test
  void aCheckboxWithoutOptionsIsASingleBoolean() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("newsletter", "checkbox")));

    assertInstanceOf(CheckBox.class, control.node());
    assertEquals(false, fxValue(control::value));
    onFx(() -> control.setValue(true));
    assertEquals(true, fxValue(control::value));
  }

  @Test
  void selectRendersAComboBoxOverTheOptionValues() {
    FieldControl control =
        onFx(() -> FieldControlFactory.create(optionField("quality", "select", "good", "poor")));

    onFx(() -> control.setValue("poor"));

    assertEquals("poor", fxValue(control::value));
  }

  @Test
  void radioRendersOneToggleGroupAndReturnsTheSelectedValue() {
    FieldControl control =
        onFx(() -> FieldControlFactory.create(optionField("urgency", "radio", "low", "high")));

    onFx(() -> control.setValue("high"));

    assertEquals("high", fxValue(control::value));
  }

  @Test
  void anUnselectedSelectOrRadioIsAnEmptyString() {
    for (String type : List.of("select", "radio")) {
      FieldControl control =
          onFx(() -> FieldControlFactory.create(optionField("a", type, "x", "y")));

      assertEquals("", fxValue(control::value), type);
    }
  }

  @Test
  void anOptionTypeWithNullOrEmptyOptionsStillRenders() {
    // FieldDto.options is nullable and the backend does not forbid an empty list.
    for (String type : List.of("select", "radio", "checkbox")) {
      assertDoesNotThrow(
          () -> onFx(() -> FieldControlFactory.create(field("a", type))),
          type + " with null options");
      assertDoesNotThrow(
          () -> onFx(() -> FieldControlFactory.create(optionField("a", type))),
          type + " with no options");
    }
  }

  @Test
  void anUnknownTypeRendersADisabledPlaceholderInsteadOfThrowing() {
    // A newer backend may add a type this build does not know; one bad field must not blank the
    // whole form, so the control renders, is inert, and contributes nothing to the submission.
    FieldControl control = onFx(() -> FieldControlFactory.create(field("mystery", "signature")));

    assertNotNull(control.node());
    assertTrue(fxValue(() -> control.node().isDisabled()));
    assertEquals("", fxValue(control::value));
    assertFalse(control.submittable(), "an unrenderable field must not be submitted");
  }

  static FieldDto field(String name, String type) {
    return FieldDto.builder().name(name).label(name).type(type).build();
  }

  static FieldDto optionField(String name, String type, String... values) {
    return FieldDto.builder()
        .name(name)
        .label(name)
        .type(type)
        .options(
            Arrays.stream(values)
                .map(v -> new FieldOptionDto(v, v.toUpperCase(Locale.ROOT)))
                .toList())
        .build();
  }
}
