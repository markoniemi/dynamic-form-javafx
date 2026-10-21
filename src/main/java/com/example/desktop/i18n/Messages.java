package com.example.desktop.i18n;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * User-visible text. No pane holds an inline string.
 *
 * <p>The bundle mirrors the key paths of the React frontend's {@code translation.json}, so a
 * translation can be lifted from either project. i18next's {@code {{name}}} placeholders become
 * {@link MessageFormat}'s positional {@code {0}}.
 */
public final class Messages {

  private static final String BUNDLE = "i18n.messages";

  private Messages() {}

  /**
   * The text for a key, or the key itself when it is missing — a typo should be visible in the UI,
   * not fatal to the screen that contains it.
   */
  public static String get(String key) {
    try {
      return ResourceBundle.getBundle(BUNDLE).getString(key);
    } catch (MissingResourceException e) {
      return key;
    }
  }

  /** As {@link #get}, with positional arguments substituted. */
  public static String format(String key, Object... args) {
    String pattern = get(key);
    return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
  }
}
