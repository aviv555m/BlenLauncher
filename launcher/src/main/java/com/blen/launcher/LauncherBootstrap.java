package com.blen.launcher;

/**
 * Bootstrap entry point that delegates to the JavaFX application class.
 * This avoids the JVM JavaFX pre-launch check on native launchers.
 */
public final class LauncherBootstrap {

    private LauncherBootstrap() {
    }

    public static void main(String[] args) {
        BlenLauncherApp.main(args);
    }
}
