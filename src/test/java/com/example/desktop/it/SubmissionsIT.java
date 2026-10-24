package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormDataService;
import com.example.desktop.api.FormService;
import com.example.desktop.auth.AuthSession;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.data.SubmissionsPane;
import com.example.dynamicform.api.FormDataDto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Submission listing and the actions each role is actually allowed to perform. */
class SubmissionsIT extends UiTest {

  @Test
  void aUserSeesOnlyOwnSubmissionsAndAnAdminSeesAll() {
    FormDataDto mine = dataApi("user", "user").submit("contact", contactValues());
    SubmissionsPane userPane = pane("user", "user");
    SubmissionsPane adminPane = pane("admin", "admin");

    onFx(userPane::refresh);
    onFx(adminPane::refresh);

    waitUntil(
        () ->
            !fxValue(userPane::rows).isEmpty()
                && fxValue(userPane::rows).stream()
                    .allMatch(d -> "user".equals(d.getSubmittedBy())));
    waitUntil(
        () -> fxValue(adminPane::rows).stream().anyMatch(d -> d.getId().equals(mine.getId())));
  }

  @Test
  void filteringByTheSelectedFormNarrowsTheRowsClientSide() {
    // GET /api/form-data has no formKey parameter, so the pane filters what it is given.
    FormDataService api = dataApi("user", "user");
    api.submit("contact", contactValues());
    api.submit("feedback", feedbackValues());
    AppState state = state("user", "user");
    SubmissionsPane pane = onFx(() -> new SubmissionsPane(api, formApi("user", "user"), state));

    onFx(pane::refresh);
    waitUntil(() -> fxValue(pane::rows).size() >= 2);
    onFx(
        () -> {
          state.selectedFormKey().set("feedback");
          pane.filterBySelectedForm(true);
        });

    assertTrue(fxValue(pane::rows).stream().allMatch(d -> "feedback".equals(d.getFormKey())));
    assertFalse(fxValue(pane::rows).isEmpty());
  }

  @Test
  void deleteIsOfferedToAnAdminAndNotToAUser() {
    FormDataDto mine = dataApi("user", "user").submit("contact", contactValues());

    SubmissionsPane userPane = pane("user", "user");
    onFx(userPane::refresh);
    waitUntil(() -> !fxValue(userPane::rows).isEmpty());
    onFx(() -> userPane.select(mine.getId()));
    assertFalse(fxValue(userPane::deleteEnabled));

    SubmissionsPane adminPane = pane("admin", "admin");
    onFx(adminPane::refresh);
    waitUntil(
        () -> fxValue(adminPane::rows).stream().anyMatch(d -> d.getId().equals(mine.getId())));
    onFx(() -> adminPane.select(mine.getId()));
    assertTrue(fxValue(adminPane::deleteEnabled));
  }

  @Test
  void anAdminIsNotOfferedEditOnSomeoneElsesSubmission() {
    // PUT /api/form-data/submission/{id} is owner-only with no admin escape, so offering the
    // action would guarantee a 403.
    FormDataDto theirs = dataApi("user", "user").submit("contact", contactValues());
    SubmissionsPane adminPane = pane("admin", "admin");
    onFx(adminPane::refresh);
    waitUntil(
        () -> fxValue(adminPane::rows).stream().anyMatch(d -> d.getId().equals(theirs.getId())));

    onFx(() -> adminPane.select(theirs.getId()));

    assertTrue(fxValue(adminPane::deleteEnabled), "an admin may still delete it");
    assertFalse(fxValue(adminPane::editEnabled), "but must not be offered an edit that 403s");
  }

  @Test
  void openingASubmissionWhoseFieldsNoLongerMatchTheFormShowsWhatItCan() {
    // A field was renamed after the submission was stored: the known values must still appear and
    // the unknown key must not break the edit view.
    Map<String, Object> stale = new LinkedHashMap<>(contactValues());
    stale.put("removedField", "orphan");
    FormDataDto saved = dataApi("user", "user").submit("contact", stale);
    SubmissionsPane pane = pane("user", "user");
    onFx(pane::refresh);
    waitUntil(() -> fxValue(pane::rows).stream().anyMatch(d -> d.getId().equals(saved.getId())));

    assertDoesNotThrow(() -> onFx(() -> pane.openForEdit(saved.getId())));

    waitUntil(() -> fxValue(() -> pane.editControlFor("name")) != null);
    assertEquals("SubmissionsIT", fxValue(() -> pane.editControlFor("name").value()));
    assertTrue(fxValue(pane::editing), "the edit view has to be on screen to be of any use");
  }

  @Test
  void savingAnEditKeepsStoredValuesThisFormCanNoLongerRender() {
    // The backend replaces `data` wholesale, so anything missing from the PUT body is deleted for
    // good. A field renamed after this submission was made must not cost the user that answer.
    FormDataService api = dataApi("user", "user");
    Map<String, Object> stale = new LinkedHashMap<>(contactValues());
    stale.put("removedField", "orphan");
    FormDataDto saved = api.submit("contact", stale);
    SubmissionsPane pane = pane("user", "user");
    onFx(pane::refresh);
    waitUntil(() -> fxValue(pane::rows).stream().anyMatch(d -> d.getId().equals(saved.getId())));

    onFx(() -> pane.openForEdit(saved.getId()));
    waitUntil(() -> fxValue(() -> pane.editControlFor("name")) != null);
    onFx(() -> pane.editorPane().setValues(Map.of("name", "Edited")));
    onFx(() -> pane.editorPane().submit());

    waitUntil(() -> "Edited".equals(api.get(saved.getId()).getData().get("name")));
    assertEquals(
        "orphan",
        api.get(saved.getId()).getData().get("removedField"),
        "an orphaned stored value must survive an edit of the fields that remain");
  }

  @Test
  void aSubmitConfirmationSurvivesTheRefreshItTriggers() {
    // Saving publishes the submission, which makes the pane reload; that reload was not asked for,
    // so it must not replace "Submission saved" with a row count.
    FormDataService api = dataApi("user", "user");
    FormDataDto saved = api.submit("contact", contactValues());
    AppState state = state("user", "user");
    SubmissionsPane pane = onFx(() -> new SubmissionsPane(api, formApi("user", "user"), state));
    onFx(pane::refresh);
    waitUntil(() -> fxValue(state.status()::get).endsWith(" submissions"));

    onFx(() -> pane.openForEdit(saved.getId()));
    waitUntil(() -> fxValue(() -> pane.editControlFor("name")) != null);
    onFx(() -> pane.editorPane().submit());
    waitUntil(() -> "Submission saved".equals(fxValue(state.status()::get)));
    waitUntil(() -> !fxValue(pane::editing));
    // A bounded negative wait: the assertion is that nothing changes, and a silent reload gives no
    // positive signal to wait on.
    settle(1500);

    assertEquals("Submission saved", fxValue(state.status()::get));
  }

  private static Map<String, Object> contactValues() {
    return Map.of(
        "name", "SubmissionsIT",
        "email", "submissions@example.com",
        "subject", "support",
        "message", "hello",
        "urgency", "low");
  }

  private static Map<String, Object> feedbackValues() {
    return Map.of(
        "productName", "Widget", "quality", "good", "features", java.util.List.of("feature1"));
  }

  private static SubmissionsPane pane(String username, String password) {
    return onFx(
        () ->
            new SubmissionsPane(
                dataApi(username, password),
                formApi(username, password),
                state(username, password)));
  }

  private static AppState state(String username, String password) {
    AppState state = new AppState();
    state.session().set(session(username, password));
    return state;
  }

  private static AuthSession session(String username, String password) {
    return AuthSession.fromToken(
        TokenClient.accessToken(username, password), JsonMapperFactory.create());
  }

  private static FormService formApi(String username, String password) {
    return new FormService(client(username, password));
  }

  private static FormDataService dataApi(String username, String password) {
    return new FormDataService(client(username, password));
  }

  private static ApiClient client(String username, String password) {
    String token = TokenClient.accessToken(username, password);
    return new ApiClient(StackFixture.backendUrl(), () -> token, JsonMapperFactory.create());
  }
}
