package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.desktop.DesktopApp;
import com.example.desktop.api.ApiClient;
import com.example.desktop.api.FormDataService;
import com.example.desktop.api.FormService;
import com.example.desktop.auth.AuthSession;
import com.example.desktop.config.DesktopConfig;
import com.example.desktop.i18n.Messages;
import com.example.desktop.json.JsonMapperFactory;
import com.example.desktop.state.AppState;
import com.example.desktop.ui.UiTest;
import com.example.desktop.ui.data.SubmissionsPane;
import com.example.desktop.ui.forms.FormListPane;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the first load of the window leaves on the status line. */
class StartupIT extends UiTest {

  @Test
  void theIgnoredConfigWarningSurvivesBothStartupLoads(@TempDir Path dir) throws IOException {
    Path file = Files.writeString(dir.resolve("desktop.properties"), "bogus.key=1\n");
    DesktopConfig config = DesktopConfig.load(file, new Properties());
    assertFalse(config.ignored().isEmpty(), "the fixture must actually produce a warning");
    String expected = Messages.format("config.ignored", String.join(", ", config.ignored()));

    String token = TokenClient.accessToken("user", "user");
    ApiClient client =
        new ApiClient(StackFixture.backendUrl(), () -> token, JsonMapperFactory.create());
    AppState state = new AppState();
    state.session().set(AuthSession.fromToken(token, JsonMapperFactory.create()));
    FormService formApi = new FormService(client);
    FormListPane list = onFx(() -> new FormListPane(formApi, state));
    SubmissionsPane submissions =
        onFx(() -> new SubmissionsPane(new FormDataService(client), formApi, state));

    onFx(() -> DesktopApp.startLoad(state, config, list, submissions));
    waitUntil(() -> !fxValue(list::formKeys).isEmpty());
    // A bounded negative wait: the assertion is that the loads leave the message alone, and a
    // silent load gives no positive signal to wait on.
    settle(1500);

    assertEquals(expected, fxValue(state.status()::get));
  }
}
