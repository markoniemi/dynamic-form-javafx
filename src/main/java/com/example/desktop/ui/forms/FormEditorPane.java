package com.example.desktop.ui.forms;

import com.example.desktop.api.ApiException;
import com.example.desktop.api.FormApi;
import com.example.desktop.i18n.Messages;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.common.BusyOverlay;
import com.example.desktop.ui.common.ErrorBanner;
import com.example.desktop.ui.field.FieldControl;
import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FieldOptionDto;
import com.example.dynamicform.api.FormDto;
import com.example.dynamicform.api.ValidationErrorDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Create, edit and delete form definitions. Admin only.
 *
 * <p>The pane is docked only for an admin, but {@link #save()} still handles a 403 rather than
 * assuming that gate held — a stale layout or a changed role should produce a message, not a
 * swallowed failure.
 */
public class FormEditorPane extends BorderPane {

  /** The types the backend's own {@code FieldDto} pattern allows. */
  private static final List<String> FIELD_TYPES =
      List.of("text", "email", "tel", "number", "date", "textarea", "select", "radio", "checkbox");

  private static final List<String> OPTION_TYPES = List.of("select", "radio", "checkbox");

  private final FormApi api;
  private final AppState state;
  private final ErrorBanner banner = new ErrorBanner();
  private final BusyOverlay busy = new BusyOverlay();

  private final TextField formKey = new TextField();
  private final TextField title = new TextField();
  private final TextField description = new TextField();
  private final VBox fieldRows = new VBox(8);
  private final List<FieldRow> rows = new ArrayList<>();
  private final Button saveButton = new Button(Messages.get("editForm.submit"));
  private final Button deleteButton = new Button(Messages.get("submissions.table.delete"));

  private String editingKey;
  private Consumer<FormDto> onSaved = saved -> {};

  public FormEditorPane(FormApi api, AppState state) {
    this.api = api;
    this.state = state;

    formKey.setPromptText(Messages.get("editForm.formKeyPlaceholder"));
    title.setPromptText(Messages.get("editForm.titlePlaceholder"));
    description.setPromptText(Messages.get("editForm.descriptionPlaceholder"));

    Button addField = new Button(Messages.get("editForm.addField"));
    addField.setOnAction(event -> addRow(new FieldRow()));
    saveButton.setOnAction(event -> save());
    deleteButton.setOnAction(event -> delete());

    VBox form =
        new VBox(
            8,
            labelled("editForm.label.formKey", formKey),
            labelled("editForm.label.title", title),
            labelled("editForm.label.description", description),
            new Label(Messages.get("editForm.fields")),
            fieldRows,
            addField);
    form.setPadding(new Insets(12));
    ScrollPane scroll = new ScrollPane(form);
    scroll.setFitToWidth(true);

    setTop(banner);
    setCenter(new StackPane(scroll, busy));
    setBottom(new ToolBar(saveButton, deleteButton));
    state.admin().addListener((obs, old, now) -> updateActions());
    editNew();
  }

  private static VBox labelled(String key, TextField field) {
    return new VBox(4, new Label(Messages.get(key)), field);
  }

  /** Starts a new, empty definition. */
  public void editNew() {
    editingKey = null;
    banner.clear();
    formKey.setDisable(false);
    formKey.clear();
    title.clear();
    description.clear();
    rows.clear();
    fieldRows.getChildren().clear();
    addRow(new FieldRow());
    saveButton.setText(Messages.get("editForm.submit"));
    updateActions();
  }

  /** Loads an existing definition for editing. Its key becomes read-only — it is the identity. */
  public void edit(FormDto form) {
    setDefinition(form);
    editingKey = form.getFormKey();
    formKey.setDisable(true);
    saveButton.setText(Messages.get("form.update"));
    updateActions();
  }

  /**
   * Both writes need {@code ROLE_ADMIN}. The pane is only docked for an admin, but a role can
   * change under a restored layout, so the buttons follow the session rather than assuming.
   * {@link #save()} still handles a 403 regardless.
   */
  private void updateActions() {
    boolean admin = state.admin().get();
    saveButton.setDisable(!admin);
    deleteButton.setDisable(!admin || editingKey == null);
  }

  /** Fills every input from a definition, without deciding whether it is a create or an update. */
  public void setDefinition(FormDto form) {
    formKey.setText(form.getFormKey() == null ? "" : form.getFormKey());
    title.setText(form.getTitle() == null ? "" : form.getTitle());
    description.setText(form.getDescription() == null ? "" : form.getDescription());
    rows.clear();
    fieldRows.getChildren().clear();
    for (FieldDto field : form.getFields() == null ? List.<FieldDto>of() : form.getFields()) {
      FieldRow row = new FieldRow();
      row.set(field);
      addRow(row);
    }
  }

  /** The definition as currently entered. */
  public FormDto collect() {
    return FormDto.builder()
        .formKey(formKey.getText())
        .title(title.getText())
        .description(description.getText())
        .fields(rows.stream().map(FieldRow::toField).toList())
        .build();
  }

  /** What the error banner is showing; empty when nothing is. */
  public String errorText() {
    return banner.text();
  }

  /** Called with the saved definition after a successful create or update. */
  public void setOnSaved(Consumer<FormDto> onSaved) {
    this.onSaved = onSaved == null ? saved -> {} : onSaved;
  }

  /** Creates or updates the form, off the FX thread. */
  public void save() {
    banner.clear();
    // Clear last attempt's marks, or a corrected field stays flagged after a successful save.
    formKey.getStyleClass().remove(FieldControl.ERROR_STYLE_CLASS);
    title.getStyleClass().remove(FieldControl.ERROR_STYLE_CLASS);
    FormDto definition = collect();
    String existing = editingKey;

    Task<FormDto> call =
        new Task<>() {
          @Override
          protected FormDto call() {
            return existing == null
                ? api.create(definition)
                : api.update(existing, definition);
          }
        };
    call.setOnSucceeded(
        event -> {
          busy.setBusy(false);
          edit(call.getValue());
          banner.show(Messages.get("form.updateSuccess"));
          onSaved.accept(call.getValue());
        });
    call.setOnFailed(
        event -> {
          busy.setBusy(false);
          report(call.getException());
        });

    busy.setBusy(true);
    start(call, "save-form");
  }

  private void delete() {
    if (editingKey == null) {
      return;
    }
    String doomed = editingKey;
    Task<Void> call =
        new Task<>() {
          @Override
          protected Void call() {
            api.delete(doomed);
            return null;
          }
        };
    call.setOnSucceeded(
        event -> {
          busy.setBusy(false);
          editNew();
        });
    call.setOnFailed(
        event -> {
          busy.setBusy(false);
          report(call.getException());
        });

    busy.setBusy(true);
    start(call, "delete-form");
  }

  private static void start(Task<?> task, String name) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Shows what the server rejected. Field errors on {@code formKey} and {@code title} are placed on
   * their inputs; everything else — including the {@code fields[0].name} paths a nested violation
   * produces — goes to the banner, which is better than vanishing.
   */
  private void report(Throwable cause) {
    if (!(cause instanceof ApiException api)) {
      banner.show(cause == null ? Messages.get("common.error") : cause.getMessage());
      return;
    }
    List<ValidationErrorDto> unplaced = new ArrayList<>();
    for (ValidationErrorDto error : api.getErrors()) {
      Map<String, TextField> placeable = Map.of("formKey", formKey, "title", title);
      TextField field = error.getField() == null ? null : placeable.get(error.getField());
      if (field == null) {
        unplaced.add(error);
      } else if (!field.getStyleClass().contains(FieldControl.ERROR_STYLE_CLASS)) {
        field.getStyleClass().add(FieldControl.ERROR_STYLE_CLASS);
      }
    }
    if (!unplaced.isEmpty()) {
      banner.showAll(unplaced);
    } else if (api.getErrors().isEmpty()) {
      banner.show(api.getDetail());
    } else {
      // Every error was placed on an input; still say something, or a rejected save looks silent.
      banner.showAll(api.getErrors());
    }
  }

  /** One editable field definition. */
  private final class FieldRow extends HBox {

    private final TextField name = new TextField();
    private final TextField label = new TextField();
    private final ComboBox<String> type = new ComboBox<>();
    private final CheckBox required = new CheckBox(Messages.get("fieldEditor.required"));
    private final TextArea options = new TextArea();

    FieldRow() {
      super(8);
      name.setPromptText(Messages.get("fieldEditor.fieldName"));
      label.setPromptText(Messages.get("fieldEditor.label"));
      type.getItems().setAll(FIELD_TYPES);
      type.setValue("text");
      options.setPromptText(Messages.get("fieldEditor.options") + ": value=Label");
      options.setPrefRowCount(2);
      // Options only mean anything for the three types that have them.
      type.valueProperty()
          .addListener((obs, old, now) -> showOptionsFor(now));
      showOptionsFor(type.getValue());

      Button remove = new Button("✕");
      remove.setTooltip(new Tooltip(Messages.get("fieldEditor.removeField")));
      remove.setOnAction(event -> removeRow(this));

      getChildren().addAll(name, label, type, required, options, remove);
      setPadding(new Insets(4));
    }

    private void showOptionsFor(String fieldType) {
      boolean visible = OPTION_TYPES.contains(fieldType);
      options.setVisible(visible);
      options.setManaged(visible);
    }

    void set(FieldDto field) {
      name.setText(field.getName());
      label.setText(field.getLabel());
      type.setValue(field.getType());
      required.setSelected(field.isRequired());
      options.setText(
          field.getOptions() == null
              ? ""
              : field.getOptions().stream()
                  .map(option -> option.getValue() + "=" + option.getLabel())
                  .reduce((a, b) -> a + "\n" + b)
                  .orElse(""));
    }

    FieldDto toField() {
      List<FieldOptionDto> parsed =
          OPTION_TYPES.contains(type.getValue()) ? parseOptions() : null;
      return FieldDto.builder()
          .name(name.getText())
          .label(label.getText())
          .type(type.getValue())
          .required(required.isSelected())
          .options(parsed == null || parsed.isEmpty() ? null : parsed)
          .build();
    }

    /** One option per line, {@code value=Label}; a line with no {@code =} is both. */
    private List<FieldOptionDto> parseOptions() {
      return Arrays.stream(options.getText().split("\\R"))
          .map(String::trim)
          .filter(line -> !line.isEmpty())
          .map(
              line -> {
                int eq = line.indexOf('=');
                return eq < 0
                    ? new FieldOptionDto(line, line)
                    : new FieldOptionDto(line.substring(0, eq), line.substring(eq + 1));
              })
          .toList();
    }
  }

  private void addRow(FieldRow row) {
    rows.add(row);
    fieldRows.getChildren().add(row);
  }

  private void removeRow(FieldRow row) {
    rows.remove(row);
    fieldRows.getChildren().remove(row);
  }
}
