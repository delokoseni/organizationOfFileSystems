package io.github.delokoseni;

import io.github.delokoseni.application.DiskInspectorApplication;
import javafx.application.Application;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(DiskInspectorApplication.class, args);
    }
}
