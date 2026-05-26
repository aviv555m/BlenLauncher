package com.blen.launcher.controller;

import com.blen.core.auth.AuthManager;
import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Controller for the login screen. Handles cracked account authentication.
 */
public class LoginScreenController {
    @FXML
    private TextField usernameField;
    @FXML
    private Button loginButton;
    @FXML
    private Button cancelButton;

    private AuthManager authManager;

    public void initialize() {
        // Default storage directory in user's home
        Path storageDir = Paths.get(System.getProperty("user.home"), ".blen-launcher");
        authManager = new AuthManager(storageDir);

        // If already logged in, skip to main screen
        if (authManager.isLoggedIn()) {
            // Run after scene attachment to avoid null getScene()/getWindow.
            Platform.runLater(this::switchToMainScreen);
        }
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();

        if (username.isEmpty()) {
            showAlert("Error", "Please enter a username.");
            return;
        }

        try {
            authManager.loginCracked(username);
            switchToMainScreen();
        } catch (Exception e) {
            showAlert("Login Failed", "Failed to login: " + e.getMessage());
        }
    }

    @FXML
    private void handleCancel() {
        if (usernameField.getScene() != null && usernameField.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    private void switchToMainScreen() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                getClass().getResource("/fxml/MainScreen.fxml"));
            javafx.scene.Scene scene = new javafx.scene.Scene(loader.load());
            scene.getStylesheets().add("/css/styles.css");

            if (usernameField.getScene() != null && usernameField.getScene().getWindow() instanceof Stage stage) {
                stage.setScene(scene);
            } else {
                throw new IllegalStateException("Login window is not ready yet");
            }
        } catch (Exception e) {
            showAlert("Error", "Failed to load main screen: " + e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
