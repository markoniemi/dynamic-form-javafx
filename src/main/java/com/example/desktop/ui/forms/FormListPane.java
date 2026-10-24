package com.example.desktop.ui.forms;

import com.example.desktop.api.FormService;
import com.example.desktop.i18n.Messages;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.common.BusyOverlay;
import com.example.desktop.ui.common.ErrorBanner;
import com.example.dynamicform.api.FormListItemDto;
import java.util.Comparator;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/**
 * The list of available forms. Selecting one publishes it to {@link AppState}; this pane holds no
 * reference to whatever shows the form, so the docking framework can create and destroy either.
 */
public class FormListPane extends BorderPane {

  private final FormService api;
  private final AppState state;
  private final ObservableList<FormListItemDto> items = FXCollections.observableArrayList();
  private final ListView<FormListItemDto> listView = new ListView<>(items);
  private final ErrorBanner banner = new ErrorBanner();
  private final BusyOverlay busy = new BusyOverlay();

  public FormListPane(FormService api, AppState state) {
    this.api = api;
    this.state = state;

    listView.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(FormListItemDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle());
              }
            });
    listView
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, old, selected) -> {
              if (selected != null) {
                state.selectedFormKey().set(selected.getFormKey());
              }
            });

    // A glyph rather than a Messages key: there is no refresh string in the shared bundle, and a
    // symbol needs no translation. The tooltip carries the words.
    Button refresh = new Button("↻");
    refresh.setTooltip(new Tooltip(Messages.get("forms.subtitle")));
    refresh.setOnAction(event -> refresh());

    setTop(banner);
    setCenter(new StackPane(listView, busy));
    setBottom(refresh);
    BorderPane.setMargin(refresh, new Insets(8));
  }

  /** Reloads the list off the FX thread. */
  public void refresh() {
    banner.clear();
    Task<List<FormListItemDto>> call =
        new Task<>() {
          @Override
          protected List<FormListItemDto> call() {
            return api.list();
          }
        };
    call.setOnSucceeded(
        event -> {
          busy.setBusy(false);
          // GET /api/forms applies no ordering, so the pane imposes one rather than showing
          // whatever arrives.
          items.setAll(
              call.getValue().stream()
                  .sorted(Comparator.comparing(FormListItemDto::getTitle))
                  .toList());
          state.status().set(Messages.format("status.formsLoaded", items.size()));
          if (items.isEmpty()) {
            banner.show(Messages.get("forms.noForms"));
          }
        });
    call.setOnFailed(
        event -> {
          busy.setBusy(false);
          Throwable cause = call.getException();
          banner.show(cause == null ? Messages.get("common.error") : cause.getMessage());
        });

    busy.setBusy(true);
    Thread thread = new Thread(call, "form-list");
    thread.setDaemon(true);
    thread.start();
  }

  /** The keys currently listed, in display order. */
  public List<String> formKeys() {
    return items.stream().map(FormListItemDto::getFormKey).toList();
  }

  /** Selects a form by key, as a double-click or a restored session would. */
  public void select(String formKey) {
    items.stream()
        .filter(item -> item.getFormKey().equals(formKey))
        .findFirst()
        .ifPresent(item -> listView.getSelectionModel().select(item));
  }
}
