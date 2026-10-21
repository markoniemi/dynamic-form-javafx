package com.example.desktop.ui.field;

import javafx.scene.Node;
import javafx.scene.control.Tooltip;

/**
 * One field of a form: the control the user sees, paired with an accessor for the value in the
 * exact JSON shape the backend expects.
 *
 * <p>Nothing outside {@code ui.field} switches on {@link com.example.dynamicform.api.FieldDto}'s
 * {@code type}; panes work through this interface instead.
 */
public interface FieldControl {

  /** Added to a control whose value the server or client rejected. */
  String ERROR_STYLE_CLASS = "field-error";

  /** The control to place in the form. */
  Node node();

  /** The field name this control belongs to, which is also the submission map's key. */
  String fieldName();

  /**
   * The value to submit, already in the right JSON type: a {@code String} for text, number and
   * date fields, a {@code List<String>} for a checkbox group, a {@code Boolean} for a lone
   * checkbox.
   */
  Object value();

  /** Populates the control from a stored submission value. Tolerates a null or wrongly typed value. */
  void setValue(Object value);

  /** Marks the control as rejected and shows the reason. */
  default void showError(String message) {
    if (!node().getStyleClass().contains(ERROR_STYLE_CLASS)) {
      node().getStyleClass().add(ERROR_STYLE_CLASS);
    }
    Tooltip.install(node(), new Tooltip(message));
  }

  /** Clears what {@link #showError} set. */
  default void clearError() {
    node().getStyleClass().remove(ERROR_STYLE_CLASS);
  }

  /**
   * Whether this control contributes to the submission. False for a field whose type this build
   * does not recognise, so one unknown field cannot corrupt the rest of the payload.
   */
  default boolean submittable() {
    return true;
  }
}
