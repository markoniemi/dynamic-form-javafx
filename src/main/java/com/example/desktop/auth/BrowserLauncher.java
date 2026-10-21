package com.example.desktop.auth;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/**
 * Opens the authorization URL for the user to log in.
 *
 * <p>The system browser is used rather than an embedded WebView, as RFC 8252 recommends: it already
 * holds the user's SSO session and works with real password managers. Tests substitute their own
 * implementation so a login can be driven without a window.
 */
@FunctionalInterface
public interface BrowserLauncher {

  void open(URI uri);

  /** Hands the URL to the desktop's default browser. */
  static BrowserLauncher systemBrowser() {
    return uri -> {
      if (!Desktop.isDesktopSupported()
          || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        throw new IllegalStateException(
            "No system browser available; cannot log in. Open this URL manually: " + uri);
      }
      try {
        Desktop.getDesktop().browse(uri);
      } catch (IOException e) {
        throw new IllegalStateException("Cannot open a browser for " + uri, e);
      }
    };
  }
}
