package io.github.delokoseni.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UiMessagesTest {
    @Test
    void applicationLabelsAreLoadedFromRussianResources() {
        assertTrue(UiMessages.text("application.title").toLowerCase(java.util.Locale.ROOT)
                .contains("файловых систем"));
        assertTrue(UiMessages.text("application.selectVolume").toLowerCase(java.util.Locale.ROOT)
                .contains("выбранный том"));
        assertTrue(UiMessages.text("tab.diagnostics").toLowerCase(java.util.Locale.ROOT)
                .contains("диагностика"));
    }
}
