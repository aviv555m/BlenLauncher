package com.blen.launcher.controller;

import com.blen.core.assets.AssetManager;
import com.blen.core.auth.AuthManager;
import com.blen.core.auth.Session;
import com.blen.core.launch.GameLauncher;
import com.blen.core.performance.SmoothEngine;
import com.blen.core.skin.SkinService;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Controller for the main launcher screen.
 * Handles version management, RAM allocation, smooth mods auto-installation, and launching.
 */
public class MainScreenController {
    @FXML
    private Label welcomeLabel;
    @FXML
    private ImageView avatarImage;
    @FXML
    private ComboBox<MinecraftVersion> versionCombo;
    @FXML
    private CheckBox smoothOptCheck;
    @FXML
    private CheckBox smoothModsCheck;
    @FXML
    private ComboBox<String> ramCombo;
    @FXML
    private Button launchButton;
    @FXML
    private Label statusLabel;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private TextArea logsArea;

    private AuthManager authManager;
    private VersionManager versionManager;
    private AssetManager assetManager;
    private SkinService skinService;
    private GameLauncher gameLauncher;
    private SmoothEngine smoothEngine;
    private Session session;
    private Process currentProcess;
    private Path installDir;

    public void initialize() {
        Path baseDir = Paths.get(System.getProperty("user.home"), ".blen-launcher");
        this.installDir = baseDir.resolve("minecraft");

        authManager = new AuthManager(baseDir);
        versionManager = new VersionManager(installDir);
        assetManager = new AssetManager(installDir);
        skinService = new SkinService(installDir);
        gameLauncher = new GameLauncher(installDir, versionManager);
        smoothEngine = new SmoothEngine(installDir);

        session = authManager.getCurrentSession();
        if (session != null) {
            welcomeLabel.setText("Welcome, " + session.getUsername() + "!");
            loadAvatarAsync(session.getUsername());
        } else {
            welcomeLabel.setText("Welcome!");
        }

        // Setup RAM options
        if (ramCombo != null) {
            ramCombo.getItems().addAll("2 GB", "4 GB", "6 GB", "8 GB", "12 GB", "16 GB");
            ramCombo.getSelectionModel().select("4 GB");
        }

        if (smoothOptCheck != null) {
            smoothOptCheck.setSelected(true);
        }
        if (smoothModsCheck != null) {
            smoothModsCheck.setSelected(true);
        }

        if (logsArea != null) {
            logsArea.clear();
        }

        loadVersions();
    }

    private void loadAvatarAsync(String username) {
        new Thread(() -> {
            try {
                Path avatarPath = skinService.downloadAndCacheAvatar(username);
                if (avatarPath != null && Files.exists(avatarPath)) {
                    Platform.runLater(() -> {
                        try {
                            if (avatarImage != null) {
                                avatarImage.setImage(new Image(avatarPath.toUri().toString()));
                            }
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    @FXML
    private void handleRefreshVersions() {
        if (versionCombo != null) {
            versionCombo.getItems().clear();
        }
        statusLabel.setText("Refreshing version list...");
        loadVersions();
    }

    private void loadVersions() {
        Task<List<MinecraftVersion>> task = new Task<>() {
            @Override
            protected List<MinecraftVersion> call() throws Exception {
                try {
                    return versionManager.getAvailableVersions();
                } catch (Exception ex) {
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
            if (versionCombo != null) {
                versionCombo.getItems().clear();
                for (MinecraftVersion v : versions) {
                    versionCombo.getItems().add(v);
                }
                if (!versions.isEmpty()) {
                    versionCombo.getSelectionModel().selectFirst();
                }
            }
            statusLabel.setText("Ready to play");
        });

        task.setOnFailed(e -> {
            statusLabel.setText("Failed to load versions: " + task.getException().getMessage());
            if (versionCombo != null) {
                versionCombo.setPromptText("No versions available");
            }
        });

        new Thread(task).start();
    }

    @FXML
    private void handleLogout() {
        try {
            authManager.logout();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/LoginScreen.fxml"));
            Scene scene = new Scene(loader.load(), 900, 560);
            scene.getStylesheets().add("/css/styles.css");

            if (launchButton.getScene() != null && launchButton.getScene().getWindow() instanceof Stage stage) {
                stage.setScene(scene);
            }
        } catch (Exception ex) {
            showAlert("Logout Error", "Failed to logout: " + ex.getMessage());
        }
    }

    @FXML
    private void handleOpenGameDir() {
        try {
            Files.createDirectories(installDir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(installDir.toFile());
            }
        } catch (Exception ex) {
            showAlert("Open Directory Error", "Could not open folder: " + ex.getMessage());
        }
    }

    @FXML
    private void handleClearLogs() {
        if (logsArea != null) {
            logsArea.clear();
        }
    }

    @FXML
    private void handleLaunch() {
        MinecraftVersion selected = versionCombo != null ? versionCombo.getValue() : null;
        if (selected == null) {
            showAlert("No Version Selected", "Please select a Minecraft version to play.");
            return;
        }

        int ramGb = 4;
        if (ramCombo != null && ramCombo.getValue() != null) {
            try {
                String ramText = ramCombo.getValue().split(" ")[0];
                ramGb = Integer.parseInt(ramText);
            } catch (Exception ignored) {
                ramGb = 4;
            }
        }

        final int selectedRam = ramGb;
        final boolean enableSmoothOpt = smoothOptCheck != null && smoothOptCheck.isSelected();
        final boolean enableSmoothMods = smoothModsCheck != null && smoothModsCheck.isSelected();

        // Disable UI during launch
        launchButton.setDisable(true);
        statusLabel.setText("Preparing...");
        progressIndicator.setVisible(true);
        if (logsArea != null) logsArea.clear();

        Task<Boolean> launchTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                MinecraftVersion versionToLaunch = selected;

                // 1. If Smooth Mods is enabled, install Fabric + Sodium
                if (enableSmoothMods) {
                    updateMessage("Configuring smooth performance mods for " + selected.getId() + "...");
                    String moddedVersionId = smoothEngine.installSmoothMods(selected.getId(), this::updateMessage);
                    if (!moddedVersionId.equals(selected.getId())) {
                        versionToLaunch = new MinecraftVersion(moddedVersionId, "release", selected.getTime());
                    }
                }

                // 2. Download and verify game files and libraries
                updateMessage("Verifying Minecraft " + versionToLaunch.getId() + " files...");
                versionManager.downloadVersion(versionToLaunch, this::updateMessage);

                // 3. Download assets (using multi-threaded asset engine)
                updateMessage("Checking assets...");
                assetManager.downloadAssets(selected.getId(), this::updateMessage);

                // 4. Launch the game
                updateMessage("Launching Minecraft " + versionToLaunch.getId() + " (RAM: " + selectedRam + "GB)...");
                currentProcess = authManager.getCurrentSession() != null
                    ? gameLauncher.launch(authManager.getCurrentSession(), versionToLaunch, selectedRam, enableSmoothOpt)
                    : null;

                if (currentProcess != null) {
                    final StringBuilder processLog = new StringBuilder();

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
                        } catch (IOException ignored) {
                        }
                    });
                    outputThread.setDaemon(true);
                    outputThread.start();

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
