package com.example.desktop.ui.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.desktop.i18n.Messages;
import com.example.desktop.ui.UiTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoginPaneTest extends UiTest {

  @Test
  void theButtonStartsTheFlowOnce() {
    AtomicInteger starts = new AtomicInteger();
    LoginPane pane = onFx(() -> new LoginPane(starts::incrementAndGet));
    onFx(() -> pane.signInButton().fire());
    assertEquals(1, starts.get());
  }

  @Test
  void theButtonIsDisabledWhileSigningIn() {
    LoginPane pane = onFx(() -> new LoginPane(() -> {}));
    onFx(() -> pane.setBusy(true));
    assertTrue(fxValue(() -> pane.signInButton().isDisabled()));
    assertEquals(Messages.get("login.inProgress"), fxValue(pane::messageText));
  }

  @Test
  void aFailedSignInLeavesTheButtonUsableWithTheReason() {
    LoginPane pane = onFx(() -> new LoginPane(() -> {}));
    onFx(
        () -> {
          pane.setBusy(true);
          pane.setBusy(false);
          pane.showMessage("Connection refused");
        });
    assertFalse(fxValue(() -> pane.signInButton().isDisabled()));
    assertEquals("Connection refused", fxValue(pane::messageText));
  }
}
