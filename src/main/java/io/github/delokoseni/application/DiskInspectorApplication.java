package io.github.delokoseni.application;

import io.github.delokoseni.controller.MainController;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class DiskInspectorApplication extends Application {
    @Override
    public void start(Stage stage) {
        MainController controller = new MainController();
        Parent view = controller.createView();
        Scene scene = new Scene(view, 1100, 720);
        stage.setTitle("File System Organization — Read-only Disk Inspector");
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
    }
}
