package dev.muguet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EngineStatusPolicyTest {
    @Test public void routineGraphicsRingDiagnosticsAreNeverOnScreen() {
        assertFalse(EngineStatusPolicy.showInGame("Graphics ring head=12 tail=12 ready=1 done=0"));
        assertFalse(EngineStatusPolicy.showInGame("Graphics ring: waiting for renderer"));
        assertFalse(EngineStatusPolicy.showInGame("Futex wait at 12345"));
        assertFalse(EngineStatusPolicy.showInGame("Native stereo audio ring attached."));
        assertFalse(EngineStatusPolicy.showInGame("Graphics initialized successfully"));
        assertFalse(EngineStatusPolicy.showInGame("Renderer: 37 FPS"));
        assertFalse(EngineStatusPolicy.showInGame("Starting engine…"));
        assertFalse(EngineStatusPolicy.showInGame(null));
    }
    @Test public void actualStartupFailuresStayVisible() {
        assertTrue(EngineStatusPolicy.showInGame("Graphics initialization failed: Vulkan surface"));
        assertTrue(EngineStatusPolicy.showInGame("Graphics validation failed: unsupported format"));
        assertTrue(EngineStatusPolicy.showInGame("Native engine library failed"));
        assertTrue(EngineStatusPolicy.showInGame("Native boot stopped: no game module"));
        assertTrue(EngineStatusPolicy.showInGame("Game files missing."));
        assertTrue(EngineStatusPolicy.showInGame("Native engine: no rendered frames after 45 seconds"));
        assertTrue(EngineStatusPolicy.showInGame("Startup failed: exception"));
    }
}
