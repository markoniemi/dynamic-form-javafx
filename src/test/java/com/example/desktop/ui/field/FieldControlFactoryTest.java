package com.example.desktop.ui.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.ui.UiTest;
import com.example.dynamicform.api.FieldDto;
import java.time.LocalDate;
import java.util.List;
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

  static FieldDto field(String name, String type) {
    return FieldDto.builder().name(name).label(name).type(type).build();
  }
}
