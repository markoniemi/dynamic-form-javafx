package com.example.desktop.ui.common;

import com.example.dynamicform.api.ValidationErrorDto;
import java.util.List;
import java.util.stream.Collectors;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * One place a pane shows what went wrong.
 *
 * <p>It exists so that an error with nowhere better to go still reaches the user: a class-level
 * validation failure carries a null field, and a message dropped because no control matched would
 * leave someone staring at a form that was rejected for no visible reason.
 */
public class ErrorBanner extends StackPane {

  private final Label label = new Label();

  public ErrorBanner() {
    getStyleClass().add("error-banner");
    label.setWrapText(true);
    getChildren().add(label);
    clear();
  }

  public void show(String message) {
    label.setText(message == null ? "" : message);
    setVisible(!label.getText().isBlank());
    setManaged(isVisible());
  }

  /** Shows every message, one per line. */
  public void showAll(List<ValidationErrorDto> errors) {
    show(
        errors.stream()
            .map(ValidationErrorDto::getMessage)
            .filter(message -> message != null && !message.isBlank())
            .collect(Collectors.joining("\n")));
  }

  public void clear() {
    show("");
  }

  /** What is currently displayed; empty when nothing is. */
  public String text() {
    return label.getText();
  }
}
