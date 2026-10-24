package com.example.desktop.ui.data;

import com.example.desktop.api.FormDataService;
import com.example.desktop.api.FormService;
import com.example.desktop.i18n.Messages;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.common.BusyOverlay;
import com.example.desktop.ui.common.ErrorBanner;
import com.example.desktop.ui.field.FieldControl;
import com.example.desktop.ui.forms.FormFillPane;
import com.example.dynamicform.api.FormDataDto;
import com.example.dynamicform.api.FormDto;
import java.util.List;
import java.util.function.Predicate;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/**
 * The submissions table, with the actions each role may actually perform.
 *
 * <p>What a caller sees depends on who they are, and the pane mirrors the backend's rules rather
 * than guessing: {@code GET /api/form-data} already returns only a plain user's own submissions,
 * delete needs {@code ROLE_ADMIN}, and update is owner-only <em>with no admin escape</em> — so an
 * admin looking at someone else's submission is offered delete but not edit.
 */
public class SubmissionsPane extends BorderPane {

  private final FormDataService dataApi;
  private final FormService formApi;
  private final AppState state;

  private final ObservableList<FormDataDto> all = FXCollections.observableArrayList();
  private final FilteredList<FormDataDto> shown = new FilteredList<>(all, row -> true);
  private final TableView<FormDataDto> table = new TableView<>(shown);
  private final ErrorBanner banner = new ErrorBanner();
  private final BusyOverlay busy = new BusyOverlay();
  private final Button editButton = new Button(Messages.get("submissions.table.edit"));
  private final Button deleteButton = new Button(Messages.get("submissions.table.delete"));
  private final Button backButton = new Button(Messages.get("editForm.backToForms"));
  private final FormFillPane editor;
  private final StackPane tableView;
  private final BorderPane editorView;

  private boolean filterBySelectedForm;

  public SubmissionsPane(FormDataService dataApi, FormService formApi, AppState state) {
    this.dataApi = dataApi;
    this.formApi = formApi;
    this.state = state;
    this.editor = new FormFillPane(dataApi, state);

    table
        .getColumns()
        .addAll(
            List.of(
                column("submissions.table.id", d -> String.valueOf(d.getId())),
                column("submissions.table.formKey", FormDataDto::getFormKey),
                column("submissions.table.submittedAt", d -> String.valueOf(d.getSubmittedAt())),
                column("submissions.table.submittedBy", FormDataDto::getSubmittedBy)));

    editButton.setOnAction(event -> openSelectedForEdit());
    deleteButton.setOnAction(event -> deleteSelected());
    Button refresh = new Button("↻");
    refresh.setOnAction(event -> refresh());

    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, selected) -> updateActions());
    state.admin().addListener((obs, old, now) -> updateActions());
    // A submission saved elsewhere (the fill pane publishes it) means this list is stale.
    state.selectedSubmission().addListener((obs, old, saved) -> load(false));
    // Keep a live filter in step when the selection moves to another form.
    state.selectedFormKey().addListener((obs, old, key) -> applyFilter());
    updateActions();

    backButton.setOnAction(event -> showTable());
    // The editor replaces the table rather than sitting in a hidden corner: before this, clicking
    // Edit populated a pane that was never in the scene graph, so the action did nothing visible.
    tableView = new StackPane(table, busy);
    editorView = new BorderPane(editor);
    editorView.setTop(new ToolBar(backButton));

    setTop(banner);
    setCenter(tableView);
    setBottom(new ToolBar(refresh, editButton, deleteButton));
    BorderPane.setMargin(table, new Insets(0));
    // A saved edit returns to the list, which the selectedSubmission listener has already
    // refreshed.
    editor.setOnSaved(saved -> showTable());
  }

  private TableColumn<FormDataDto, String> column(
      String titleKey, java.util.function.Function<FormDataDto, String> value) {
    TableColumn<FormDataDto, String> column = new TableColumn<>(Messages.get(titleKey));
    column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
    return column;
  }

  /** Reloads off the FX thread. */
  public void refresh() {
    load(true);
  }

  /**
   * Reloads without announcing the count in the status line: a reload nobody asked for must not
   * overwrite the message of whatever caused it.
   */
  public void refreshSilently() {
    load(false);
  }

  /**
   * Loads the rows. Only a refresh somebody asked for announces its count: one driven by a listener
   * or a callback would otherwise overwrite the message the user's own action just produced.
   */
  private void load(boolean announce) {
    banner.clear();
    Task<List<FormDataDto>> call =
        new Task<>() {
          @Override
          protected List<FormDataDto> call() {
            return dataApi.list();
          }
        };
    call.setOnSucceeded(
        event -> {
          busy.setBusy(false);
          all.setAll(call.getValue());
          if (announce) {
            state.status().set(Messages.format("status.submissionsLoaded", all.size()));
          }
          if (all.isEmpty()) {
            banner.show(Messages.get("submissions.noSubmissions"));
          }
        });
    call.setOnFailed(
        event -> {
          busy.setBusy(false);
          Throwable cause = call.getException();
          banner.show(cause == null ? Messages.get("common.error") : cause.getMessage());
        });

    busy.setBusy(true);
    Thread thread = new Thread(call, "submissions");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Narrows the table to the form selected elsewhere. Client-side because the endpoint takes no
   * query parameters at all.
   */
  public void filterBySelectedForm(boolean on) {
    filterBySelectedForm = on;
    applyFilter();
  }

  private void applyFilter() {
    String key = state.selectedFormKey().get();
    Predicate<FormDataDto> predicate =
        filterBySelectedForm && key != null && !key.isBlank()
            ? row -> key.equals(row.getFormKey())
            : row -> true;
    shown.setPredicate(predicate);
  }

  /** The rows currently displayed, after any filter. */
  public List<FormDataDto> rows() {
    return List.copyOf(shown);
  }

  /** Selects a row by submission id. */
  public void select(long id) {
    shown.stream()
        .filter(row -> row.getId() != null && row.getId() == id)
        .findFirst()
        .ifPresent(row -> table.getSelectionModel().select(row));
  }

  /** Whether delete is offered: admin only, and only with a row selected. */
  public boolean deleteEnabled() {
    return !deleteButton.isDisabled();
  }

  /** Whether edit is offered: the caller's own submission only, admin or not. */
  public boolean editEnabled() {
    return !editButton.isDisabled();
  }

  /** The edit view's control for a field, or null if the editor is not open on that form. */
  public FieldControl editControlFor(String fieldName) {
    return editor.controlFor(fieldName);
  }

  /** The pane holding the submission being edited. */
  public FormFillPane editorPane() {
    return editor;
  }

  /** Whether the edit view is showing rather than the table. */
  public boolean editing() {
    return getCenter() == editorView;
  }

  /** Returns to the list, abandoning any unsaved edit. */
  public void showTable() {
    setCenter(tableView);
  }

  /** Opens a submission for editing, loading its form definition off the FX thread. */
  public void openForEdit(long id) {
    all.stream()
        .filter(row -> row.getId() != null && row.getId() == id)
        .findFirst()
        .ifPresent(this::openForEdit);
  }

  private void openSelectedForEdit() {
    FormDataDto selected = table.getSelectionModel().getSelectedItem();
    if (selected != null) {
      openForEdit(selected);
    }
  }

  private void openForEdit(FormDataDto submission) {
    Task<FormDto> load =
        new Task<>() {
          @Override
          protected FormDto call() {
            return formApi.get(submission.getFormKey());
          }
        };
    load.setOnSucceeded(
        event -> {
          busy.setBusy(false);
          // Values whose fields the form no longer declares are ignored by the fill pane rather
          // than rejected, and it keeps them for the update, so a renamed field neither stops the
          // submission from opening nor gets deleted when it is saved.
          editor.editSubmission(load.getValue(), submission);
          setCenter(editorView);
        });
    load.setOnFailed(
        event -> {
          busy.setBusy(false);
          Throwable cause = load.getException();
          banner.show(cause == null ? Messages.get("common.error") : cause.getMessage());
        });

    busy.setBusy(true);
    Thread thread = new Thread(load, "load-submission-form");
    thread.setDaemon(true);
    thread.start();
  }

  private void deleteSelected() {
    FormDataDto selected = table.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    Task<Void> call =
        new Task<>() {
          @Override
          protected Void call() {
            dataApi.delete(selected.getId());
            return null;
          }
        };
    call.setOnSucceeded(event -> load(false));
    call.setOnFailed(
        event -> {
          busy.setBusy(false);
          Throwable cause = call.getException();
          banner.show(cause == null ? Messages.get("common.error") : cause.getMessage());
        });

    busy.setBusy(true);
    Thread thread = new Thread(call, "delete-submission");
    thread.setDaemon(true);
    thread.start();
  }

  private void updateActions() {
    FormDataDto selected = table.getSelectionModel().getSelectedItem();
    boolean hasRow = selected != null;
    deleteButton.setDisable(!hasRow || !state.admin().get());
    editButton.setDisable(!hasRow || !ownedByCurrentUser(selected));
  }

  /**
   * Update is owner-only server-side, with no admin escape, so offering an admin an edit on someone
   * else's submission would guarantee a 403.
   */
  private boolean ownedByCurrentUser(FormDataDto submission) {
    if (submission == null || state.session().get() == null) {
      return false;
    }
    return state.session().get().username().equals(submission.getSubmittedBy());
  }
}
