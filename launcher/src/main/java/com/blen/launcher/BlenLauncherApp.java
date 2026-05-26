package com.blen.launcher;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Main entry point for the Blen Launcher.
 */
public class BlenLauncherApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Load and show login screen
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/LoginScreen.fxml"));
        Scene scene = new Scene(loader.load(), 900, 560);
        scene.getStylesheets().add("/css/styles.css");

        primaryStage.setTitle("Blen Launcher");
        Image icon = new Image(getClass().getResourceAsStream("/icons/blen.png"));
        primaryStage.getIcons().add(icon);
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
