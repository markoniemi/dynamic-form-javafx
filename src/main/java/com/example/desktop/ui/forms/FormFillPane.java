package com.example.desktop.ui.forms;

import com.example.desktop.api.ApiException;
import com.example.desktop.api.FormDataApi;
import com.example.desktop.i18n.Messages;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.common.BusyOverlay;
import com.example.desktop.ui.common.ErrorBanner;
import com.example.desktop.ui.field.FieldControl;
import com.example.desktop.ui.field.FieldControlFactory;
import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FormDataDto;
import com.example.dynamicform.api.FormDto;
import com.example.dynamicform.api.ValidationErrorDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Renders a form definition and submits what the user fills in.
 *
 * <p>It never switches on a field's type — {@link FieldControlFactory} owns that — and it never
 * touches HTTP directly: the call goes through {@link FormDataApi} on a background thread, with the
 * result applied back on the FX thread.
 */
public class FormFillPane extends BorderPane {

  /** Deliberately loose: the server is the authority, this only catches an obvious typo early. */
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private final FormDataApi api;
  private final AppState state;
  private final ErrorBanner banner = new ErrorBanner();
  private final BusyOverlay busy = new BusyOverlay();
  private final VBox fields = new VBox(12);
  private final Button submitButton = new Button(Messages.get("form.submit"));
  private final Map<String, FieldControl> controls = new LinkedHashMap<>();

  private FormDto form;
  private Long editingSubmissionId;
  private Map<String, Object> editingOriginalData = Map.of();
  private Consumer<FormDataDto> onSaved = saved -> {};

  public FormFillPane(FormDataApi api, AppState state) {
    this.api = api;
    this.state = state;

    fields.setPadding(new Insets(12));
    ScrollPane scroll = new ScrollPane(fields);
    scroll.setFitToWidth(true);

    submitButton.setOnAction(event -> submit());
    submitButton.setDisable(true);

    setTop(banner);
    setCenter(new StackPane(scroll, busy));
    setBottom(submitButton);
    BorderPane.setMargin(submitButton, new Insets(8, 12, 12, 12));
  }

  /** Replaces the shown form. Clears any previous values, errors and edit target. */
  public void show(FormDto form) {
    this.form = form;
    this.editingSubmissionId = null;
    this.editingOriginalData = Map.of();
    banner.clear();
    controls.clear();
    fields.getChildren().clear();

    if (form == null) {
      submitButton.setDisable(true);
      return;
    }
    for (FieldDto field : form.getFields()) {
      FieldControl control = FieldControlFactory.create(field);
      controls.put(field.getName(), control);
      Label label = new Label(field.isRequired() ? field.getLabel() + " *" : field.getLabel());
      fields.getChildren().add(new VBox(4, label, control.node()));
    }
    submitButton.setDisable(false);
    submitButton.setText(Messages.get("form.submit"));
  }

  /** Switches to editing an existing submission, pre-filled with its stored values. */
  public void editSubmission(FormDto form, FormDataDto submission) {
    show(form);
    this.editingSubmissionId = submission.getId();
    // Kept so an update does not drop stored answers this form can no longer render: the backend
    // replaces `data` wholesale, so anything missing from the PUT body is deleted for good.
    this.editingOriginalData =
        submission.getData() == null ? Map.of() : Map.copyOf(submission.getData());
    setValues(submission.getData());
    submitButton.setText(Messages.get("form.update"));
  }

  /** Shows a message without a form, for a caller that could not load one. */
  public void showMessage(String message) {
    banner.show(message);
  }

  /** Called with the saved submission after a successful submit or update. */
  public void setOnSaved(Consumer<FormDataDto> onSaved) {
    this.onSaved = onSaved == null ? saved -> {} : onSaved;
  }

  /**
   * Populates the controls from stored data. Keys the form no longer declares are ignored rather
   * than rejected — a field can be renamed after a submission was made, and the rest of that
   * submission is still worth showing.
   */
  public void setValues(Map<String, Object> data) {
    if (data == null) {
      return;
    }
    data.forEach(
        (name, value) -> {
          FieldControl control = controls.get(name);
          if (control != null) {
            control.setValue(value);
          }
        });
  }

  /** The control for a field, or null when the form has none by that name. */
  public FieldControl controlFor(String fieldName) {
    return controls.get(fieldName);
  }

  /** What is currently in the error banner; empty when nothing is. */
  public String bannerText() {
    return banner.text();
  }

  /**
   * The values to submit, in the JSON shapes the backend stores verbatim. A field whose type this
   * build does not recognise is left out entirely rather than sent as an empty value.
   */
  public Map<String, Object> collect() {
    // When editing, start from what was stored. The backend replaces `data` wholesale, so a key
    // this form no longer declares — a renamed field, or one whose type this build cannot render —
    // would be deleted from the database by an otherwise innocent save.
    Map<String, Object> data = new LinkedHashMap<>(editingOriginalData);
    controls.forEach(
        (name, control) -> {
          if (control.submittable()) {
            data.put(name, control.value());
          }
        });
    return data;
  }

  /** Validates, then submits or updates off the FX thread. */
  public void submit() {
    if (form == null) {
      return;
    }
    banner.clear();
    controls.values().forEach(FieldControl::clearError);

    List<ValidationErrorDto> problems = validate();
    if (!problems.isEmpty()) {
      showErrors(problems);
      // Marking the controls is not enough: the mark is a style class and the reason is a tooltip,
      // so without this the button would look simply dead to anyone not hovering the right field.
      banner.showAll(problems);
      return;
    }

    Map<String, Object> data = collect();
    Long submissionId = editingSubmissionId;
    String formKey = form.getFormKey();

    Task<FormDataDto> call =
        new Task<>() {
          @Override
          protected FormDataDto call() {
            return submissionId == null
                ? api.submit(formKey, data)
                : api.update(submissionId, data);
          }
        };
    call.setOnSucceeded(
        event -> {
          setBusy(false);
          banner.show(
              Messages.get(submissionId == null ? "form.success" : "form.updateSuccess"));
          // Published rather than pushed: the submissions pane listens for this, so neither pane
          // needs a reference to the other and the docking framework can destroy either freely.
          state.selectedSubmission().set(call.getValue());
          onSaved.accept(call.getValue());
        });
    call.setOnFailed(
        event -> {
          setBusy(false);
          report(call.getException());
        });

    // Disabled for the duration: the overlay only covers the centre, so without this two clicks
    // would create two submissions.
    setBusy(true);
    Thread thread = new Thread(call, "submit-" + formKey);
    thread.setDaemon(true);
    thread.start();
  }

  private void setBusy(boolean working) {
    busy.setBusy(working);
    submitButton.setDisable(working || form == null);
  }

  /** Client-side checks only. Anything subtler is the server's call. */
  private List<ValidationErrorDto> validate() {
    List<ValidationErrorDto> problems = new ArrayList<>();
    for (FieldDto field : form.getFields()) {
      FieldControl control = controls.get(field.getName());
      if (control == null || !control.submittable()) {
        continue;
      }
      Object value = control.value();
      boolean empty =
          value == null
              || (value instanceof String text && text.isBlank())
              || (value instanceof List<?> list && list.isEmpty());

      if (field.isRequired() && empty) {
        problems.add(
            new ValidationErrorDto(
                field.getName(), Messages.format("form.required", field.getLabel()), "NotBlank"));
      } else if ("email".equals(field.getType())
          && value instanceof String text
          && !text.isBlank()
          && !EMAIL.matcher(text).matches()) {
        problems.add(
            new ValidationErrorDto(
                field.getName(),
                Messages.format("form.invalidEmail", field.getLabel()),
                "Email"));
      }
    }
    return problems;
  }

  /**
   * Marks each error against its control. Anything that names no visible control — including the
   * class-level errors the backend sends with a null field — goes to the banner instead of being
   * dropped.
   */
  public void showErrors(List<ValidationErrorDto> errors) {
    Set<ValidationErrorDto> unplaced = new LinkedHashSet<>();
    for (ValidationErrorDto error : errors) {
      FieldControl control = error.getField() == null ? null : controls.get(error.getField());
      if (control == null) {
        unplaced.add(error);
      } else {
        control.showError(error.getMessage());
      }
    }
    if (!unplaced.isEmpty()) {
      banner.showAll(List.copyOf(unplaced));
    }
  }

  private void report(Throwable cause) {
    if (cause instanceof ApiException api && !api.getErrors().isEmpty()) {
      showErrors(api.getErrors());
      return;
    }
    if (cause instanceof ApiException api) {
      banner.show(api.getDetail());
      return;
    }
    banner.show(cause == null ? Messages.get("form.error") : String.valueOf(cause.getMessage()));
  }
}
