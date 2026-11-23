package io.github.delokoseni.util;

import java.util.Locale;
import java.util.ResourceBundle;

public final class UiMessages {
    private static final ResourceBundle RUSSIAN =
            ResourceBundle.getBundle("io.github.delokoseni.ui", Locale.forLanguageTag("ru"));

    private UiMessages() {
    }

    public static String text(String key) {
        return RUSSIAN.getString(key);
    }
}
