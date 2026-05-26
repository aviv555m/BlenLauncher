package com.blen.launcher.controller;

import com.blen.core.assets.AssetManager;
import com.blen.core.auth.AuthManager;
import com.blen.core.auth.Session;
import com.blen.core.launch.GameLauncher;
import com.blen.core.skin.SkinService;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Controller for the main launcher screen. Handles version selection and game launching.
 */
public class MainScreenController {
    @FXML
    private Label welcomeLabel;
    @FXML
    private ComboBox<MinecraftVersion> versionCombo;
    @FXML
    private Button launchButton;
    @FXML
    private Label statusLabel;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private javafx.scene.control.TextArea logsArea;

    private AuthManager authManager;
    private VersionManager versionManager;
    private AssetManager assetManager;
    private SkinService skinService;
    private GameLauncher gameLauncher;
    private Session session;
    private Process currentProcess;

    public void initialize() {
        Path installDir = Paths.get(System.getProperty("user.home"), ".blen-launcher", "minecraft");
        authManager = new AuthManager(Paths.get(System.getProperty("user.home"), ".blen-launcher"));
        versionManager = new VersionManager(installDir);
        assetManager = new AssetManager(installDir);
        skinService = new SkinService(installDir);
        gameLauncher = new GameLauncher(installDir, versionManager);

        session = authManager.getCurrentSession();
        if (session != null) {
            welcomeLabel.setText("Welcome, " + session.getUsername() + "!");
        }

        // Ensure logs area is clear
        if (logsArea != null) {
            logsArea.clear();
        }

        // Load versions in background
        loadVersions();
    }

    private void loadVersions() {
        Task<List<MinecraftVersion>> task = new Task<>() {
            @Override
            protected List<MinecraftVersion> call() throws Exception {
                try {
                    return versionManager.getAvailableVersions();
                } catch (Exception ex) {
                    // try a more forgiving call for releases only or fallback to empty list
                    try {
                        return versionManager.getReleaseVersions();
                    } catch (Exception ex2) {
                        return java.util.Collections.emptyList();
                    }
                }
            }
        };

        task.setOnSucceeded(e -> {
            List<MinecraftVersion> versions = task.getValue();
            for (MinecraftVersion v : versions) {
                versionCombo.getItems().add(v);
            }
            if (!versions.isEmpty()) {
                // Default to latest release
                versionCombo.getSelectionModel().selectFirst();
            }
        });

        task.setOnFailed(e -> {
            statusLabel.setText("Failed to load versions: " + task.getException().getMessage());
            // provide a friendly hint and keep the launch button disabled
            versionCombo.setPromptText("No versions available");
        });

        new Thread(task).start();
    }

    @FXML
    private void handleLaunch() {
        MinecraftVersion selected = versionCombo.getValue();
        if (selected == null) {
            showAlert("No Version Selected", "Please select a version to play.");
            return;
        }

        // Disable UI during launch
        launchButton.setDisable(true);
        statusLabel.setText("Preparing...");
        progressIndicator.setVisible(true);
        if (logsArea != null) logsArea.clear();

        Task<Boolean> launchTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                // Always verify version files so missing libraries get repaired.
                updateMessage("Verifying Minecraft " + selected.getId() + " files...");
                versionManager.downloadVersion(selected, this::updateMessage);

                // Verify/download missing assets.
                updateMessage("Preparing assets (first launch can take a few minutes)...");
                assetManager.downloadAssets(selected.getId(), this::updateMessage);

                // Launch the game
                updateMessage("Launching Minecraft " + selected.getId() + "...");
                currentProcess = authManager.getCurrentSession() != null
                    ? gameLauncher.launch(authManager.getCurrentSession(), selected)
                    : null;

                if (currentProcess != null) {
                    final StringBuilder processLog = new StringBuilder();

                    // Read and display game output in UI log box
                    Thread outputThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(currentProcess.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                System.out.println("[MC] " + line);
                                synchronized (processLog) {
                                    processLog.append(line).append("\n");
                                }
                                String uiLine = line;
                                Platform.runLater(() -> {
                                    if (logsArea != null) {
                                        logsArea.appendText("[MC] " + uiLine + "\n");
                                    }
                                });
                            }
                        } catch (IOException ex) {
                            // Process ended
                        }
                    });
                    outputThread.setDaemon(true);
                    outputThread.start();

                    // Wait for process to exit and surface non-zero exit as an error.
                    int exitCode = currentProcess.waitFor();
                    if (exitCode != 0) {
                        String tail;
                        synchronized (processLog) {
                            String all = processLog.toString();
                            int from = Math.max(0, all.length() - 1200);
                            tail = all.substring(from);
                        }
                        throw new IOException("Minecraft exited with code " + exitCode +
                                (tail.isBlank() ? "" : "\nLast output:\n" + tail));
                    }
                    return true;
                }
                return false;
            }
        };

        launchTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            Platform.runLater(() -> {
                statusLabel.setText(newMsg);
                if (logsArea != null) {
                    logsArea.appendText(newMsg + "\n");
                }
            });
        });

        launchTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                statusLabel.setText("Minecraft closed.");
                if (logsArea != null) {
                    logsArea.appendText("Minecraft closed normally.\n");
                }
                launchButton.setDisable(false);
                progressIndicator.setVisible(false);
            });
        });

        launchTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                statusLabel.setText("Error: " + launchTask.getException().getMessage());
                launchButton.setDisable(false);
                progressIndicator.setVisible(false);
            });
        });

        new Thread(launchTask).start();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
