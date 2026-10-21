package com.example.desktop.ui.field;

import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FieldOptionDto;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

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
      case "select" -> select(field);
      case "radio" -> radio(field);
      case "checkbox" -> checkbox(field);
      // Not an error: a newer backend may serve a type this build predates, and one unrenderable
      // field must not blank the rest of the form. Null is in here too — the backend declares
      // type @NotBlank, but a non-conforming server must still degrade rather than throw.
      case null, default -> unknown(field);
    };
  }

  private static FieldControl select(FieldDto field) {
    ComboBox<FieldOptionDto> combo = new ComboBox<>();
    combo.getItems().setAll(options(field));
    combo.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(FieldOptionDto option) {
            return option == null ? "" : option.getLabel();
          }

          @Override
          public FieldOptionDto fromString(String label) {
            return null; // not editable
          }
        });
    return new SelectFieldControl(field.getName(), combo);
  }

  private static FieldControl radio(FieldDto field) {
    ToggleGroup group = new ToggleGroup();
    VBox box = new VBox(4);
    for (FieldOptionDto option : options(field)) {
      RadioButton button = new RadioButton(option.getLabel());
      button.setUserData(option.getValue());
      button.setToggleGroup(group);
      box.getChildren().add(button);
    }
    return new RadioFieldControl(field.getName(), box, group);
  }

  /**
   * With options, a group whose value is the list of selected values; without, a lone checkbox
   * whose value is a boolean.
   *
   * <p>The desktop sends a one-element list for a single-option group, where the React client sends
   * the option's value or {@code false}. One rule inside this client is worth more than matching a
   * shape the frontend itself renders inconsistently — its read-only view treats the value as a
   * boolean regardless. No seeded form has a one-option checkbox.
   */
  private static FieldControl checkbox(FieldDto field) {
    List<FieldOptionDto> options = options(field);
    if (options.isEmpty()) {
      return new BooleanFieldControl(field.getName(), new CheckBox(field.getLabel()));
    }
    VBox box = new VBox(4);
    List<CheckBox> boxes = new ArrayList<>();
    for (FieldOptionDto option : options) {
      CheckBox check = new CheckBox(option.getLabel());
      check.setUserData(option.getValue());
      boxes.add(check);
      box.getChildren().add(check);
    }
    return new CheckboxGroupFieldControl(field.getName(), box, List.copyOf(boxes));
  }

  private static FieldControl unknown(FieldDto field) {
    TextField placeholder = new TextField();
    placeholder.setPromptText("Unsupported field type: " + field.getType());
    placeholder.setDisable(true);
    return new UnknownFieldControl(field.getName(), placeholder);
  }

  /** {@code FieldDto.options} is nullable, and nothing in the backend forbids an empty list. */
  private static List<FieldOptionDto> options(FieldDto field) {
    return field.getOptions() == null ? List.of() : field.getOptions();
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

  private record SelectFieldControl(String fieldName, ComboBox<FieldOptionDto> combo)
      implements FieldControl {

    @Override
    public Node node() {
      return combo;
    }

    @Override
    public Object value() {
      FieldOptionDto selected = combo.getValue();
      return selected == null ? "" : selected.getValue();
    }

    @Override
    public void setValue(Object value) {
      String wanted = value == null ? "" : String.valueOf(value);
      combo.setValue(
          combo.getItems().stream()
              .filter(option -> option.getValue().equals(wanted))
              .findFirst()
              .orElse(null));
    }
  }

  private record RadioFieldControl(String fieldName, VBox box, ToggleGroup group)
      implements FieldControl {

    @Override
    public Node node() {
      return box;
    }

    @Override
    public Object value() {
      Toggle selected = group.getSelectedToggle();
      return selected == null ? "" : String.valueOf(selected.getUserData());
    }

    @Override
    public void setValue(Object value) {
      String wanted = value == null ? "" : String.valueOf(value);
      group.getToggles().stream()
          .filter(toggle -> wanted.equals(toggle.getUserData()))
          .findFirst()
          .ifPresentOrElse(
              toggle -> group.selectToggle(toggle), () -> group.selectToggle(null));
    }
  }

  private record CheckboxGroupFieldControl(String fieldName, VBox box, List<CheckBox> boxes)
      implements FieldControl {

    @Override
    public Node node() {
      return box;
    }

    @Override
    public Object value() {
      return boxes.stream()
          .filter(CheckBox::isSelected)
          .map(check -> String.valueOf(check.getUserData()))
          .toList();
    }

    /**
     * Accepts a list, and also a bare value: the React client stores a one-option checkbox as the
     * option's value string (or {@code false}), so a submission made there would otherwise open
     * with the box clear and lose that answer on save.
     */
    @Override
    public void setValue(Object value) {
      Collection<?> selected =
          switch (value) {
            case null -> List.of();
            case Collection<?> collection -> collection;
            case Boolean ignored -> List.of();
            default -> List.of(value);
          };
      List<String> wanted = selected.stream().map(String::valueOf).toList();
      boxes.forEach(check -> check.setSelected(wanted.contains(String.valueOf(check.getUserData()))));
    }
  }

  private record BooleanFieldControl(String fieldName, CheckBox check) implements FieldControl {

    @Override
    public Node node() {
      return check;
    }

    @Override
    public Object value() {
      return check.isSelected();
    }

    @Override
    public void setValue(Object value) {
      check.setSelected(Boolean.parseBoolean(String.valueOf(value)));
    }
  }

  /** A field whose type this build does not know: visible, inert, and never submitted. */
  private record UnknownFieldControl(String fieldName, TextField placeholder)
      implements FieldControl {

    @Override
    public Node node() {
      return placeholder;
    }

    @Override
    public Object value() {
      return "";
    }

    @Override
    public void setValue(Object value) {
      // nothing to show it in
    }

    @Override
    public boolean submittable() {
      return false;
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
