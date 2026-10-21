package com.example.desktop.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.desktop.api.ApiClient;
import com.example.desktop.api.ApiPaths;
import com.example.desktop.json.JsonMapperFactory;
import org.junit.jupiter.api.Test;

/** Proves the stack boots and that both halves of it — backend and auth server — agree. */
class ConfigIT {

  @Test
  void backendReportsTheAuthServerIssuer() {
    ApiClient client =
        new ApiClient(StackFixture.backendUrl(), () -> null, JsonMapperFactory.create());

    assertEquals("http://localhost:9000", client.getText(ApiPaths.CONFIG_ISSUER_URI));
  }

  @Test
  void adminCanLogInHeadlessly() {
    String token = TokenClient.accessToken("admin", "admin");

    assertFalse(token.isBlank());
  }
}
