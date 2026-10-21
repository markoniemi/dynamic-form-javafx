package com.example.desktop.ui.field;

import com.example.dynamicform.api.FieldDto;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.UnaryOperator;
import javafx.scene.Node;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

/**
 * Maps a {@link FieldDto}'s {@code type} to a control and a value accessor.
 *
 * <p>This is the only place in the client that knows the nine field types. The value shapes are
 * dictated by the backend's behaviour, not chosen here: it stores the submission map as JSON
 * without inspecting the form, so whatever type a control reports is what is persisted and what
 * the React frontend has to be able to read.
 */
public final class FieldControlFactory {

  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

  private FieldControlFactory() {}

  /** Builds the control for a field. Must be called on the JavaFX application thread. */
  public static FieldControl create(FieldDto field) {
    return switch (field.getType()) {
      case "text", "email", "tel" -> text(field, new TextField());
      case "textarea" -> text(field, new TextArea());
      case "number" -> number(field);
      case "date" -> date(field);
      default -> throw new IllegalArgumentException("Unsupported field type: " + field.getType());
    };
  }

  private static FieldControl text(FieldDto field, TextField input) {
    input.setPromptText(field.getPlaceholder());
    return new TextFieldControl(field.getName(), TextInputLike.of(input));
  }

  private static FieldControl text(FieldDto field, TextArea input) {
    input.setPromptText(field.getPlaceholder());
    input.setPrefRowCount(4);
    return new TextFieldControl(field.getName(), TextInputLike.of(input));
  }

  /**
   * A number is submitted as a <em>string</em>, matching the React client, which registers a plain
   * text input and sends {@code event.target.value} verbatim. Sending a JSON number here would
   * make the stored data mixed-type across the two clients.
   */
  private static FieldControl number(FieldDto field) {
    TextField input = new TextField();
    input.setPromptText(field.getPlaceholder());
    // Strips what cannot belong rather than rejecting the whole change, so pasting "$1,234" keeps
    // 1234 instead of silently leaving the field as it was.
    UnaryOperator<TextFormatter.Change> numeric =
        change -> {
          if (!change.isContentChange()) {
            return change;
          }
          change.setText(change.getText().replaceAll("[^0-9.\\-]", ""));
          return change.getControlNewText().matches("-?\\d*(\\.\\d*)?") ? change : null;
        };
    input.setTextFormatter(new TextFormatter<>(numeric));
    return new TextFieldControl(field.getName(), TextInputLike.of(input));
  }

  private static FieldControl date(FieldDto field) {
    DatePicker picker = new DatePicker();
    picker.setPromptText(field.getPlaceholder());
    return new DateFieldControl(field.getName(), picker);
  }

  /** Lets one implementation serve both {@link TextField} and {@link TextArea}. */
  private interface TextInputLike {
    Node node();

    String getText();

    void setText(String text);

    static TextInputLike of(TextField field) {
      return new TextInputLike() {
        @Override
        public Node node() {
          return field;
        }

        @Override
        public String getText() {
          return field.getText();
        }

        @Override
        public void setText(String text) {
          field.setText(text);
        }
      };
    }

    static TextInputLike of(TextArea area) {
      return new TextInputLike() {
        @Override
        public Node node() {
          return area;
        }

        @Override
        public String getText() {
          return area.getText();
        }

        @Override
        public void setText(String text) {
          area.setText(text);
        }
      };
    }
  }

  private record TextFieldControl(String fieldName, TextInputLike input) implements FieldControl {

    @Override
    public Node node() {
      return input.node();
    }

    @Override
    public Object value() {
      String text = input.getText();
      return text == null ? "" : text;
    }

    @Override
    public void setValue(Object value) {
      input.setText(value == null ? "" : String.valueOf(value));
    }
  }

  private record DateFieldControl(String fieldName, DatePicker picker) implements FieldControl {

    @Override
    public Node node() {
      return picker;
    }

    /** ISO {@code yyyy-MM-dd}, and {@code ""} when empty — exactly what an HTML date input sends. */
    @Override
    public Object value() {
      LocalDate date = picker.getValue();
      return date == null ? "" : ISO.format(date);
    }

    @Override
    public void setValue(Object value) {
      String text = value == null ? "" : String.valueOf(value);
      if (text.isBlank()) {
        picker.setValue(null);
        return;
      }
      try {
        picker.setValue(LocalDate.parse(text, ISO));
      } catch (DateTimeParseException e) {
        // A stored value that is not a date: show nothing rather than refuse to open the form.
        picker.setValue(null);
      }
    }
  }
}
