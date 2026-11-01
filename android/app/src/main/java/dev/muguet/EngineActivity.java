package dev.muguet;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import org.json.JSONObject;

// Hosts the vendored wasm2c engine core. Unlike GameActivity (which uses
// NativeActivity + winit for the Rust/wasmtime runtime), this uses a plain
// SurfaceView so we own the ANativeWindow handed to the Vulkan renderer, and the
// touch HUD sits directly on top as ordinary child views - no sub-window hack.
//
// The engine boots on its own native thread inside nativeStart; this activity
// feeds it input, audio and the surface, and mirrors status text. With the
// headless graphics stub it will boot and run its non-graphics subsystems but
// not render; that is the expected state until the Vulkan renderer lands.
public final class EngineActivity extends Activity {
    // Load the renderer under a try/catch in loadEngineCore(), not during class
    // initialization. A missing library must produce a visible error, not an
    // Activity that opens and immediately closes.
    // JNI (registered natively via RegisterNatives).
    private static native void nativeMemoryPressure(int level);
    private static native void nativeThermalLevel(int status);
    private static native int nativeRegister();
    private static native boolean nativeCacheActivity(Object activity);
    private static native void nativeSetHeapMb(int mb);
    private static native void nativeSetLogPath(String path);
    // Native-only startup: apply the launcher preferences to the C engine and
    // Rust Vulkan renderer before either starts its worker threads.
    private static native void nativeConfigure(String[] graphics, int cpuWorkers,
                                                float streamerBudget, String[] rendererVars);
    private static native void nativeStart(String gameDir, String manifestPath, int startMode,
                                           int width, int height, int frameLimit);
    private static native void nativeAttachSurface(android.view.Surface surface);
    private static native void nativeUpdatePad(String[] keys, int touchButtons, boolean horn,
                                               float lookDX, float lookDY, boolean analog);
    private static native void nativeUpdatePadAnalog(float lx, float ly, float rx, float ry,
                                                     float lt, float rt, int buttons);
    private static native int nativeAudio(short[] out, int maxFrames);
    private static native boolean nativePauseMenuActive();
    private static native int nativeLastInputDevice();
    private static native int nativeTouchContext();
    private static native int nativeFps();
    private static native long[] nativePerfStats();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SurfaceView surface;
    private TouchControls controls;
    private TextView status, fpsText;
    private ThermalStatus thermalStatus;
    private InputManagerCompat devices;
    private AudioTrack audio;
    private Thread audioThread;
    private volatile boolean running, muted, surfaceReady, engineCoreLoaded;
    private boolean focused, controller, inCar;
    private int frameLimit = 30;
    // Render the Vulkan surface at a reduced internal resolution and let
    // SurfaceView scale it to the display. Touch HUD stays at device resolution.
    private int renderWidth, renderHeight;
    private float nativeRenderScale = 0.75f;
    private float lx, ly, rx, ry, lt, rt;
    private final HashSet<String> keyboardKeys = new HashSet<>();
    private final HashSet<Integer> padKeys = new HashSet<>();
    private float accLookX, accLookY;
    // The game polls the virtual pad independently of the Java 60 Hz pump.
    // Retain a recent swipe briefly so its camera input cannot fall between
    // polls at 20-40 game FPS; stop immediately when the finger lifts.
    private float lastTouchLookX, lastTouchLookY;
    private long lastTouchLookAt;
    private long fpsAt, fpsFrames, skippedAt, lastFps;
    private long nativeStartUptimeMs;
    private boolean missingFrameWarningShown;
    private String statusMessage = "Starting engine…";

    private long lastMemoryWarningAt;
    private long lastNativeMemorySampleAt;
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastMemoryWarningAt < 10000) return;
        lastMemoryWarningAt = now;
        if (engineCoreLoaded) {
            nativeMemoryPressure(level);
            Log.i("Muguet", "[memory] Android trim level " + level + " forwarded to renderer");
        }
    }

    @Override public void onCreate(Bundle state) {
        // Initialize the Vulkan proxy in this process before native rendering.
        if (!DriverBridge.prepare(this)) {
            android.util.Log.e("MuguetDriver", DriverBridge.status());
        }
        super.onCreate(state);
        thermalStatus = new ThermalStatus(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        FrameLayout root = new FrameLayout(this);
        surface = new SurfaceView(this);
        JSONObject surfaceConfig = readConfig();
        double requestedScale = surfaceConfig.optDouble("nightlane_native_render_scale", 0.75);
        nativeRenderScale = Double.isFinite(requestedScale)
            ? (float) Math.max(0.50, Math.min(1.0, requestedScale)) : 0.75f;
        int[] display = displaySize();
        renderWidth = (Math.max(320, Math.round(display[0] * nativeRenderScale)) + 1) & ~1;
        renderHeight = (Math.max(180, Math.round(display[1] * nativeRenderScale)) + 1) & ~1;
        // Fixed-size SurfaceView still fills the screen, with Android scaling
        // its smaller Vulkan buffer. A 75% scale roughly halves pixel work.
        surface.getHolder().setFixedSize(renderWidth, renderHeight);
        Log.i("NightlaneEngine", "Internal render surface: " + renderWidth + "x"
            + renderHeight + " (" + Math.round(nativeRenderScale * 100) + "%)");
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                // Surface creation can race JNI registration on startup.
                if (engineCoreLoaded) nativeAttachSurface(h.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                // The engine reads its back-buffer size at boot; after that the
                // renderer owns resize. Until it exists, keep the reported size in
                // step so a future renderer picks the right extent.
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                if (engineCoreLoaded) nativeAttachSurface(null);
            }
        });
        root.addView(surface, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        controls = new TouchControls(this,
            LookSensitivity.fromConfig(new File(getFilesDir(), "config.json")),
            (dx, dy) -> {
                // Native-only touch look is delivered by nativeUpdatePad, never
                // GameActivity.nativeLook (old Wasmtime JNI).
                accLookX = Math.max(-360f, Math.min(360f, accLookX + dx));
                accLookY = Math.max(-360f, Math.min(360f, accLookY + dy));
            });
        controls.setVisibility(View.VISIBLE);
        root.addView(controls, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(0xffffffff); status.setBackgroundColor(0xb0000000);
        status.setTextSize(16); status.setPadding(16, 8, 16, 8); status.setText(statusMessage);
        root.addView(status, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        status.setVisibility(View.GONE);

        fpsText = new TextView(this);
        fpsText.setTextColor(0xccffffff); fpsText.setTextSize(12);
        fpsText.setPadding(8, 8, 8, 8); fpsText.setShadowLayer(4, 0, 0, 0xff000000);
        fpsText.setTextSize(11);
        fpsText.setMaxLines(2);
        fpsText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        fpsText.setText("FPS  |  Thermal loading…  |  GPU statistics loading…");
        root.addView(fpsText, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));

        devices = new InputManagerCompat(this, new InputManagerCompat.Listener() {
            @Override public void controllersChanged(boolean connected) {
                controller = connected;
                if (connected) controls.setVisibility(View.GONE);
                else controls.setVisibility(View.VISIBLE);
                clearInput();
            }
        });
        setContentView(root);

        if (!loadEngineCore()) {
            setStatus("Native engine library failed. Return to the launcher for details and Export logs.");
            android.widget.Button back = new android.widget.Button(this);
            back.setText("Return to launcher");
            back.setOnClickListener(v -> finish());
            FrameLayout.LayoutParams controls = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            root.addView(back, controls);
            return;
        }
        engineCoreLoaded = true;
        if (surfaceReady && surface.getHolder().getSurface().isValid())
            nativeAttachSurface(surface.getHolder().getSurface());
        startEngine();
    }

    // The engine core is only in the builds that ship it. Loading it can fail
    // there, and that must not take the app down with an UnsatisfiedLinkError.
    private boolean loadEngineCore() {
        try {
            System.loadLibrary("muguet");
            System.loadLibrary("gta5engine");
            int registration = nativeRegister();
            if (registration != 0) throw new IllegalStateException(
                "native JNI method registration failed: " + registration);
            return true;
        } catch (Throwable t) {
            Log.e("Muguet", "native engine core not available", t);
            reportStartupFailure("Native library load/registration failed: " + t);
            return false;
        }
    }

    private void reportStartupFailure(String message) {
        Log.e("NightlaneEngine", message);
        try {
            Files.write(new File(getFilesDir(), "nightlane-last-launch-error.txt").toPath(),
                message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e("NightlaneEngine", "Could not save startup error", e);
        }
    }

    private void startEngine() {
        try {
            if (!nativeCacheActivity(this))
                throw new IllegalStateException("Native engine could not register its Android activity");
            // Captures both the C engine and Rust Vulkan stderr, unlike the
            // Wasmtime-only muguet.log. The launcher exports them together.
            nativeSetLogPath(new File(getFilesDir(), "nightlane-native.log").getAbsolutePath());
            JSONObject config = readConfig();
            muted = config.optBoolean("mute", false);
            frameLimit = nativeTargetFps(config);
            int startMode = config.optInt("start_mode", 2);
            // EngineHost reads PG_ENGINE_HEAP_MB before boot; set it from the
            // chosen ceiling and optional physical-RAM budget. This does not
            // guarantee Android will allow all of that memory to be resident.
            android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
            if (manager != null) manager.getMemoryInfo(memory);
            int heapGb = HeapPolicy.limit(config.optInt("android_heap_gb", 5), memory.totalMem,
                config.optBoolean("android_auto_heap", true));
            nativeSetHeapMb(heapGb * 1024);

            File gameDir = gameFolder();
            File manifest = new File(gameDir, "data/manifest.json");
            if (!manifest.isFile()) {
                setStatus("Game files missing. Return to the launcher and install the game first.");
                reportStartupFailure("Missing game manifest: " + manifest.getAbsolutePath());
                return;
            }
            int width = renderWidth > 0 ? renderWidth : displaySize()[0];
            int height = renderHeight > 0 ? renderHeight : displaySize()[1];
            Log.i("NightlaneEngine", "Starting native engine at " + width + "x" + height
                + " instead of full display resolution to reduce GPU load");
            // The old Wasmtime startup used to apply these arguments in Rust.
            // Native-only launches must explicitly forward them over JNI.
            nativeConfigure(nativeGraphicsArgs(config),
                Math.max(1, Math.min(8, config.optInt("native_cpu_workers", 2))),
                (float) config.optDouble("streamer_percentage", 0.35),
                nativeRendererOptions(config, frameLimit));
            Log.i("NightlaneEngine", "Native graphics preferences applied; requested FPS " + frameLimit);
            nativeStartUptimeMs = android.os.SystemClock.elapsedRealtime();
            nativeStart(gameDir.getAbsolutePath(), manifest.getAbsolutePath(), startMode,
                        width, height, frameLimit);
            if (!muted) startAudio();
            running = true;
            handler.post(tick);
        } catch (Throwable t) {
            setStatus("Startup failed: " + t);
            reportStartupFailure("Native engine initialization failed: " + t);
        }
    }

    // The engine reads its back-buffer size at boot. SurfaceView has no size yet
    // in onCreate, so fall back to the real display metrics.
    private int[] displaySize() {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return new int[] { m.widthPixels, m.heightPixels };
    }

    // The game folder is the one the user picked in the launcher, exactly like
    // the wasmtime path uses (Rust resolves it from config.json). The imported
    // copy in app storage is only the fallback for a folder that was never
    // picked.
    private File gameFolder() {
        String picked = readConfig().optString("game_folder_path", "").trim();
        if (!picked.isEmpty()) {
            File dir = new File(picked);
            if (new File(dir, "data/manifest.json").isFile()) return dir;
        }
        return new File(getFilesDir(), "game");
    }

    private JSONObject readConfig() {
        try { return new JSONObject(new String(Files.readAllBytes(
                new File(getFilesDir(), "config.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    // Keep this list synchronized with the low phone defaults in EngineHost.c.
    // Append only allowlisted overrides, never arbitrary engine command strings.
    private static String[] nativeGraphicsArgs(JSONObject cfg) {
        java.util.LinkedHashMap<String, String> args = new java.util.LinkedHashMap<>();
        for (String entry : new String[] {
            "-shaderQuality=1", "-textureQuality=0", "-pedVariety=0",
            "-vehicleVariety=0", "-shadowQuality=-1", "-reflectionQuality=0",
            "-particleQuality=0", "-grassQuality=0", "-cityDensity=0",
            "-lodScale=0", "-pedLodBias=0", "-vehicleLodBias=0"
        }) {
            int equal = entry.indexOf('=');
            args.put(entry.substring(0, equal), entry);
        }
        final String allowed = "|-shaderQuality|-textureQuality|-pedVariety|-vehicleVariety"
            + "|-shadowQuality|-reflectionQuality|-particleQuality|-grassQuality"
            + "|-cityDensity|-lodScale|-pedLodBias|-vehicleLodBias|-waterQuality"
            + "|-anisotropicQualityLevel|-postFX|-MotionBlurStrength|-noInGameDOF"
            + "|-shadowSoftness|-SSAO|-farclip|-fogVolumes|-particleShadows"
            + "|-shadowLongShadows|-HDStreamingInFlight|-nolodlights";
        org.json.JSONArray overrides = cfg.optJSONArray("extra_args");
        if (overrides != null) for (int i = 0; i < overrides.length(); i++) {
            String argument = overrides.optString(i, "");
            int equal = argument.indexOf('=');
            if (equal < 1 || argument.length() > 90) continue;
            String name = argument.substring(0, equal);
            String value = argument.substring(equal + 1);
            if (!(allowed + "|").contains("|" + name + "|")) continue;
            if (!value.matches("[a-zA-Z0-9.+-]{1,20}")) continue;
            args.put(name, argument);
        }
        return args.values().toArray(new String[0]);
    }

    // GTA's -frameLimit takes an internal 0/1/2 VSYNC mode, not literal FPS.
    // The C bridge selects that mode; the renderer can also pace at 24/30/42.
    private static int nativeTargetFps(JSONObject cfg) {
        int value = cfg.optInt("fps_limit", 30);
        org.json.JSONArray args = cfg.optJSONArray("extra_args");
        if (args != null) for (int i = 0; i < args.length(); i++) {
            String s = args.optString(i, "");
            if (!s.startsWith("-frameLimit=")) continue;
            try {
                int parsed = Integer.parseInt(s.substring("-frameLimit=".length()));
                if (parsed == 24 || parsed == 30 || parsed == 42 || parsed == 60)
                    value = parsed;
            } catch (NumberFormatException ignored) {}
        }
        return value <= 0 ? 0 : Math.max(24, Math.min(60, value));
    }

    private static String[] nativeRendererOptions(JSONObject cfg, int fps) {
        java.util.ArrayList<String> vars = new java.util.ArrayList<>();
        String queue = cfg.optString("nightlane_frame_queue", "low_latency");
        if (!queue.equals("minimal") && !queue.equals("original")) queue = "low_latency";
        vars.add("NIGHTLANE_FRAME_QUEUE=" + queue);
        vars.add("NIGHTLANE_SHADOW_FIX=" + (cfg.optBoolean("nightlane_shadow_fix", true) ? "1" : "0"));
        String mode = cfg.optString("nightlane_present", "native");
        if (!mode.equals("smooth") && !mode.equals("sharp")) mode = "native";
        vars.add("NIGHTLANE_PRESENT=" + mode);
        double sharpness = cfg.optDouble("nightlane_sharpness", 0.3);
        if (!Double.isFinite(sharpness)) sharpness = 0.3;
        vars.add("NIGHTLANE_SHARPNESS=" + String.format(java.util.Locale.US, "%.3f",
            Math.max(0.0, Math.min(0.5, sharpness))));
        vars.add("NIGHTLANE_THERMAL_ADAPTIVE="
            + (cfg.optBoolean("nightlane_thermal_adaptive", true) ? "1" : "0"));
        // Existing launcher toggle now controls the native filesystem too.
        // This is an OS cache hint, not a speculative user-data download.
        vars.add("PG_HTTPFS_READAHEAD_KB=" +
            (cfg.optBoolean("nightlane_read_ahead", true) ? "64" : "0"));
        int pipelines = cfg.optInt("nightlane_pipeline_workers", 2);
        vars.add("NIGHTLANE_PIPELINE_WORKERS=" +
            (pipelines == 1 || pipelines == 4 ? pipelines : 2));
        if (cfg.optBoolean("gpu_software_clip", false))
            vars.add("MUGUET_GPU_SOFTWARE_CLIP=1");
        if (cfg.optBoolean("gpu_cpu_bc", false))
            vars.add("MUGUET_GPU_CPU_BC=1");
        // A literal 30/42/24 FPS target must be implemented by frame pacing
        // rather than passing those integers to the game's internal frame mode.
        if (fps > 0 && fps < 60)
            vars.add("MUGUET_RENDER_FPS_LIMIT=" + fps);
        return vars.toArray(new String[0]);
    }

    // Called from the native engine thread via the log bridge. Never touch views
    // directly here; setStatus marshals to the UI thread itself.
    public void setStatus(final String text) {
        statusMessage = text;
        runOnUiThread(() -> {
            if (status == null) return;
            status.setText(text);
            boolean serious = text.startsWith("Startup failed") || text.startsWith("Graphics")
                || text.startsWith("Game files missing") || text.startsWith("Native engine library failed")
                || text.contains("no rendered frames");
            status.setVisibility(serious ? View.VISIBLE : View.GONE);
        });
    }

    // --- audio ---------------------------------------------------------------
    private void startAudio() {
        int rate = 48000, channelConfig = AudioFormat.CHANNEL_OUT_STEREO,
            encoding = AudioFormat.ENCODING_PCM_16BIT;
        int min = AudioTrack.getMinBufferSize(rate, channelConfig, encoding);
        int bufferBytes = Math.max(min, 4096 * 4);
        audio = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(encoding).setSampleRate(rate).setChannelMask(channelConfig).build())
            .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM).build();
        audio.play();
        audioThread = new Thread(() -> {
            short[] block = new short[4096];
            while (running) {
                int frames = 0;
                try { frames = nativeAudio(block, block.length / 2); } catch (Exception ignored) {}
                if (frames > 0) audio.write(block, 0, frames * 2);
                else { try { Thread.sleep(8); } catch (InterruptedException e) { return; } }
            }
        }, "engine-audio");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    // --- input ---------------------------------------------------------------
    private void clearInput() {
        keyboardKeys.clear(); padKeys.clear();
        lx = ly = rx = ry = lt = rt = 0; accLookX = accLookY = 0;
        lastTouchLookX = lastTouchLookY = 0; lastTouchLookAt = 0;
        if (controls != null) controls.release();
        nativeUpdatePad(new String[0], 0, false, 0, 0, false);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            // 60 Hz input pump; independent of the engine's frame rate.
            TouchControls.Input touch = controls != null ? controls.input() : new TouchControls.Input();

            // Switch the HUD between on-foot and in-car layouts, as GameActivity did.
            int context = nativeTouchContext();
            boolean nowInCar = context != 0;
            // `this` inside this anonymous Runnable is the Runnable, so the outer
            // field must be reached explicitly.
            if (nowInCar != EngineActivity.this.inCar) {
                EngineActivity.this.inCar = nowInCar;
                if (controls != null) controls.setInCar(nowInCar);
            }

            HashSet<String> keys = new HashSet<>(touch.keys);
            keys.addAll(keyboardKeys);
            int touchButtons = touch.buttons;
            boolean horn = touch.horn;

            if (controller) {
                // A real gamepad keeps its analog sticks; this is what preserves
                // GTA's analog driving and assisted aim. Face/shoulder buttons
                // and the d-pad are still mapped to engine pad bits.
                int padButtons = 0;
                if (down(KeyEvent.KEYCODE_BUTTON_A)) padButtons |= 1 << 6;      // A
                if (down(KeyEvent.KEYCODE_BUTTON_B)) padButtons |= 1 << 5;      // B
                if (down(KeyEvent.KEYCODE_BUTTON_X)) padButtons |= 1 << 7;      // X
                if (down(KeyEvent.KEYCODE_BUTTON_Y)) padButtons |= 1 << 4;      // Y
                if (down(KeyEvent.KEYCODE_BUTTON_L1)) padButtons |= 1 << 2;     // L1
                if (down(KeyEvent.KEYCODE_BUTTON_R1)) padButtons |= 1 << 3;     // R1
                if (down(KeyEvent.KEYCODE_BUTTON_SELECT)) padButtons |= 1 << 8; // Select
                if (down(KeyEvent.KEYCODE_BUTTON_START)) padButtons |= 1 << 11; // Start
                if (down(KeyEvent.KEYCODE_DPAD_UP)) padButtons |= 1 << 12;
                if (down(KeyEvent.KEYCODE_DPAD_DOWN)) padButtons |= 1 << 14;
                if (down(KeyEvent.KEYCODE_DPAD_LEFT)) padButtons |= 1 << 15;
                if (down(KeyEvent.KEYCODE_DPAD_RIGHT)) padButtons |= 1 << 13;
                float gas = Math.max(rt, down(KeyEvent.KEYCODE_BUTTON_R2) ? 1f : 0f);
                float brake = Math.max(lt, down(KeyEvent.KEYCODE_BUTTON_L2) ? 1f : 0f);
                nativeUpdatePadAnalog(lx, ly, rx, ry, brake, gas, padButtons);
                accLookX = 0; accLookY = 0;
                updateFps();
                handler.postDelayed(this, 16);
                return;
            }

            // Touch / keyboard: map onto the engine's virtual pad.
            if (lx < -0.3f) keys.add("KeyA"); if (lx > 0.3f) keys.add("KeyD");
            if (ly < -0.3f) keys.add("KeyW"); if (ly > 0.3f) keys.add("KeyS");
            boolean gasTouch = rt > 0.2f || down(KeyEvent.KEYCODE_BUTTON_R2);
            if (gasTouch) touchButtons |= 1;
            if (lt > 0.2f || down(KeyEvent.KEYCODE_BUTTON_L2)) touchButtons |= 4;
            if (down(KeyEvent.KEYCODE_BUTTON_A)) keys.add("Enter");
            if (down(KeyEvent.KEYCODE_BUTTON_B)) { keys.add("KeyR"); keys.add("Backspace"); }
            if (down(KeyEvent.KEYCODE_BUTTON_X)) keys.add("Space");
            if (down(KeyEvent.KEYCODE_BUTTON_Y)) keys.add("KeyF");
            if (down(KeyEvent.KEYCODE_BUTTON_L1)) keys.add("Tab");
            if (down(KeyEvent.KEYCODE_BUTTON_R1)) keys.add("KeyQ");
            if (down(KeyEvent.KEYCODE_BUTTON_START)) keys.add("Escape");
            if (down(KeyEvent.KEYCODE_BUTTON_SELECT)) keys.add("KeyV");
            // Swipes need to survive long enough for the native gamepad
            // polling thread to read the stick. The retained input is bounded
            // to 48 ms and only active while a camera/aim finger is still down.
            // It is never repeated after release or a lost-focus clearInput().
            boolean cameraFinger = controls != null && controls.hasCameraFinger();
            long now = System.nanoTime();
            float lookX = accLookX, lookY = accLookY;
            if (cameraFinger && (lookX != 0 || lookY != 0)) {
                lastTouchLookX = lookX;
                lastTouchLookY = lookY;
                lastTouchLookAt = now;
            } else if (cameraFinger && lastTouchLookAt != 0
                    && now - lastTouchLookAt < 48_000_000L) {
                float remaining = 1f - (float)(now - lastTouchLookAt) / 48_000_000f;
                lookX = lastTouchLookX * remaining;
                lookY = lastTouchLookY * remaining;
            } else {
                lastTouchLookX = lastTouchLookY = 0;
                lastTouchLookAt = 0;
            }
            if (rx != 0 || ry != 0) {
                lookX += rx * 96f;
                lookY += ry * 96f;
            }

            nativeUpdatePad(keys.toArray(new String[0]), touchButtons, horn,
                            lookX, lookY, true);
            accLookX = 0; accLookY = 0;

            updateFps();
            handler.postDelayed(this, 16);
        }
    };

    private boolean down(int key) { return padKeys.contains(key); }
    private static boolean isPad(int source) {
        return (source & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
            || (source & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (isPad(event.getSource())) {
            int key = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) padKeys.add(key); else padKeys.remove(key);
            return true;
        }
        String mapped = keyboardKey(event.getKeyCode());
        if (mapped != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) keyboardKeys.add(mapped); else keyboardKeys.remove(mapped);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private static String keyboardKey(int key) {
        if (key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) return "Key" + (char) ('A' + key - KeyEvent.KEYCODE_A);
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) return "Digit" + (key - KeyEvent.KEYCODE_0);
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "ShiftLeft";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "ControlLeft";
            case KeyEvent.KEYCODE_ALT_LEFT: return "AltLeft";
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            default: return null;
        }
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (isPad(e.getSource())) {
            lx = e.getAxisValue(MotionEvent.AXIS_X); ly = e.getAxisValue(MotionEvent.AXIS_Y);
            rx = e.getAxisValue(MotionEvent.AXIS_Z); ry = e.getAxisValue(MotionEvent.AXIS_RZ);
            lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void updateFps() {
        long now = System.nanoTime();
        // Do not call JNI or update TextView until a full second has elapsed.
        if (fpsAt != 0 && now - fpsAt < 1_000_000_000L) return;
        int thermalLevel = thermalStatus.level();
        nativeThermalLevel(thermalLevel);
        // Sample Android process PSS once every 10 seconds, not every frame.
        // Previous Wasmtime telemetry did this; native reports lacked it.
        long uptime = android.os.SystemClock.elapsedRealtime();
        if (uptime - lastNativeMemorySampleAt > 10000) {
            lastNativeMemorySampleAt = uptime;
            android.os.Debug.MemoryInfo m = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(m);
            Log.i("NightlaneEngine", "[native-telemetry] pss_mb=" + m.getTotalPss() / 1024
                + " native_heap_mb=" + android.os.Debug.getNativeHeapAllocatedSize() / 1048576
                + " thermal=" + thermalLevel + " fps=" + lastFps);
        }
        long[] stats = nativePerfStats();
        if (stats == null || stats.length < PerformanceHud.STATS_COUNT) return;
        // The native game can park on a perfectly ordinary futex while another
        // thread fails to start. Don't call the futex a crash: identify the
        // actual symptom (zero frames) and tell the tester how to get logs.
        if (!missingFrameWarningShown && nativeStartUptimeMs > 0
                && stats[0] == 0
                && android.os.SystemClock.elapsedRealtime() - nativeStartUptimeMs > 45000
                && !statusMessage.startsWith("Native boot stopped:")
                && !statusMessage.startsWith("Graphics initialization failed:")
                && !statusMessage.startsWith("Startup failed:")) {
            missingFrameWarningShown = true;
            setStatus("Native engine: no rendered frames after 45 seconds. "
                + "Return to the launcher and Export logs; the new file includes renderer startup details.");
        }
        if (fpsAt == 0) {
            fpsAt = now;
            fpsFrames = stats[0];
            skippedAt = stats[5];
            return;
        }
        double seconds = (now - fpsAt) / 1e9;
        float fps = (float) Math.max(0, stats[0] - fpsFrames) / (float) seconds;
        long skipped = Math.max(0, stats[5] - skippedAt);
        fpsAt = now;
        fpsFrames = stats[0];
        skippedAt = stats[5];
        lastFps = (long) fps;
        if (fpsText != null) fpsText.setText(PerformanceHud.format(fps, stats, skipped, seconds, ThermalStatus.label(thermalLevel)));
    }

    // --- lifecycle -----------------------------------------------------------
    @Override protected void onResume() {
        super.onResume();
        if (!engineCoreLoaded) return;
        running = true; devices.start(); handler.post(tick);
    }
    @Override protected void onPause() {
        running = false; handler.removeCallbacks(tick);
        if (engineCoreLoaded) { devices.stop(); clearInput(); }
        if (audio != null) { try { audio.pause(); } catch (Exception ignored) {} }
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.focused = hasFocus;
        if (!hasFocus) clearInput();
    }
    @Override protected void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} }
        if (audioThread != null) audioThread.interrupt();
        super.onDestroy();
        // Native wasm2c memory and pthreads are process-owned, and the core
        // has no safe in-process reset. Isolate each new Play in a fresh
        // :engine process, matching the existing Wasmtime :game lifecycle.
        if (engineCoreLoaded) android.os.Process.killProcess(android.os.Process.myPid());
    }
    @Override public void onBackPressed() {
        // Back leaves the engine; the engine runs in its own process so a later
        // Play starts clean.
        super.onBackPressed();
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}