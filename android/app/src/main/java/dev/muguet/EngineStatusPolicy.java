package dev.muguet;

/** Only genuine failures may become visible. Routine native ring/progress
 * diagnostics go into nightlane-native.log, not over the game view. */
final class EngineStatusPolicy {
    private EngineStatusPolicy() {}

    static boolean showInGame(String message) {
        if (message == null) return false;
        return message.startsWith("Startup failed:")
            || message.startsWith("Native boot stopped:")
            || message.startsWith("Graphics initialization failed:")
            || message.startsWith("Graphics validation failed:")
            || message.startsWith("Game files missing")
            || message.startsWith("Native engine library failed")
            || message.startsWith("Native engine: no rendered frames");
    }
}
