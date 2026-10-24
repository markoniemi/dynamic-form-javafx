package com.example.desktop.state;

import com.example.desktop.auth.AuthSession;
import com.example.dynamicform.api.FormDataDto;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableBooleanValue;

/**
 * The state the panes share.
 *
 * <p>Panes never reference each other — they read and write this, so the docking framework can
 * create, destroy and re-dock them freely without anything holding a stale reference.
 */
public class AppState {

  private final ObjectProperty<AuthSession> session = new SimpleObjectProperty<>();
  private final StringProperty selectedFormKey = new SimpleStringProperty();
  private final ObjectProperty<FormDataDto> selectedSubmission = new SimpleObjectProperty<>();
  private final StringProperty status = new SimpleStringProperty("");
  private final ObservableBooleanValue admin;

  public AppState() {
    // Derived, not stored, so no caller has to remember to keep it in step with the session.
    admin =
        Bindings.createBooleanBinding(
            () -> session.get() != null && session.get().isAdmin(), session);
  }

  public ObjectProperty<AuthSession> session() {
    return session;
  }

  public StringProperty selectedFormKey() {
    return selectedFormKey;
  }

  public ObjectProperty<FormDataDto> selectedSubmission() {
    return selectedSubmission;
  }

  /** The status line text. Written on the FX thread only; not an error channel. */
  public StringProperty status() {
    return status;
  }

  /** Whether to offer admin-only actions. A display decision; the backend is the authority. */
  public ObservableBooleanValue admin() {
    return admin;
  }
}
