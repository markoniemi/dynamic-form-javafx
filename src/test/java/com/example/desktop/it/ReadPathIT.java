package com.example.desktop.it;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormDataService;
import com.example.desktop.api.FormService;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.forms.FormFillPane;
import com.example.desktop.ui.forms.FormListPane;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The read path end to end: the real backend, the real panes, no mocks. */
class ReadPathIT extends UiTest {

  @Test
  void listingAFormThenFillingAndSubmittingItCreatesASubmission() {
    FormService formApi = formApi("user", "user");
    FormDataService dataApi = formDataApi("user", "user");
    AppState state = new AppState();
    FormListPane list = onFx(() -> new FormListPane(formApi, state));
    FormFillPane fill = onFx(() -> new FormFillPane(dataApi, state));
    // The panes never reference each other; DesktopApp wires them through AppState exactly like
    // this.
    state.selectedFormKey().addListener((obs, old, key) -> fill.show(formApi.get(key)));

    onFx(list::refresh);
    waitUntil(() -> fxValue(list::formKeys).contains("contact"));

    onFx(() -> state.selectedFormKey().set("contact"));
    waitUntil(() -> fxValue(() -> fill.controlFor("name")) != null);
    onFx(() -> fill.setValues(contactValues()));
    onFx(fill::submit);

    waitUntil(
        () ->
            dataApi.list().stream()
                .anyMatch(d -> "contact".equals(d.getFormKey()) && hasTester(d.getData())));
  }

  @Test
  void theListShowsEverySeededForm() {
    FormListPane list = onFx(() -> new FormListPane(formApi("user", "user"), new AppState()));

    onFx(list::refresh);

    waitUntil(() -> fxValue(list::formKeys).containsAll(List.of("contact", "feedback", "survey")));
  }

  private static boolean hasTester(Map<String, Object> data) {
    return "ReadPathIT".equals(data.get("name"));
  }

  private static Map<String, Object> contactValues() {
    return Map.of(
        "name", "ReadPathIT",
        "email", "readpath@example.com",
        "subject", "support",
        "message", "hello",
        "urgency", "low");
  }

  private static FormService formApi(String username, String password) {
    return new FormService(client(username, password));
  }

  private static FormDataService formDataApi(String username, String password) {
    return new FormDataService(client(username, password));
  }

  private static ApiClient client(String username, String password) {
    String token = TokenClient.accessToken(username, password);
    return new ApiClient(StackFixture.backendUrl(), () -> token, JsonMapperFactory.create());
  }
}
