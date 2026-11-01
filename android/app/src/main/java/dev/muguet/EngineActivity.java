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
    private static native void nativeSetTargetingMode(int mode);
    private static native int nativeAudio(short[] out, int maxFrames);
    private static native long nativeAudioBufferState();
    private static native long nativeAudioProducedFrames();
    private static native long nativeAudioCallbackCount();
    private static native void nativeAudioReport(String message);
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
    // Audio playback continues independently of the 60 Hz UI/input pump;
    // game process startup may run well before the first onResume tick.
    private volatile boolean audioThreadRunning, audioPaused, audioStarted;
    private long lastAudioWatchdogAtMs, lastAudioWatchdogProduced, lastAudioWatchdogCallbacks;
    private long lastAudioWatchdogPlayback;
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

        float touchGain = LookSensitivity.fromConfig(new File(getFilesDir(), "config.json"));
        Log.i("NightlaneInput", "Native touch look sensitivity: " + touchGain + "x");
        // A camera finger is sampled as a held virtual right stick rather
        // than a one-tick swipe impulse the engine may never observe.
        controls = new TouchControls(this, touchGain, true);
        controls.setVisibility(View.VISIBLE);
        root.addView(controls, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(0xffffffff); status.setBackgroundColor(0xb0000000);
        status.setTextSize(12);
        status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(12, 7, 12, 7);
        status.setText(statusMessage);
        FrameLayout.LayoutParams errorLocation = new FrameLayout.LayoutParams(
            Math.min(dp(340), display[0] - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP | android.view.Gravity.LEFT);
        errorLocation.leftMargin = dp(12);
        errorLocation.topMargin = dp(48);
        root.addView(status, errorLocation);
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
            Log.i("NightlaneAudio", "Launch audio mute=" + muted + " (native wasm2c)");
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
            java.util.ArrayList<String> nativeOptions = new java.util.ArrayList<>(
                java.util.Arrays.asList(nativeRendererOptions(config, frameLimit)));
            // App-private cache; native GPU thread persists driver-built pipeline
            // binaries here for reuse on the next launch, if the driver supports it.
            nativeOptions.add("NIGHTLANE_PIPELINE_CACHE_DIR=" +
                new File(getFilesDir(), "nightlane-gpu-cache").getAbsolutePath());
            nativeConfigure(nativeGraphicsArgs(config),
                Math.max(1, Math.min(8, config.optInt("native_cpu_workers", 2))),
                (float) config.optDouble("streamer_percentage", 0.35),
                nativeOptions.toArray(new String[0]));
            Log.i("NightlaneEngine", "Native graphics preferences applied; requested FPS " + frameLimit);
            nativeStartUptimeMs = android.os.SystemClock.elapsedRealtime();
            nativeStart(gameDir.getAbsolutePath(), manifest.getAbsolutePath(), startMode,
                        width, height, frameLimit);
            // Free aim lets the right thumb steer the reticle while LT is held.
            // Users who prefer GTA's assisted lock-on can disable it in Settings.
            if (config.optBoolean("nightlane_touch_free_aim", true))
                nativeSetTargetingMode(3);
            running = true;
            if (!muted) startAudio();
            else Log.w("NightlaneAudio", "Audio disabled by saved Mute setting");
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
        // Revert to allocating a new staging buffer on each GPU readback
        // without changing correctness or requiring an APK rebuild.
        vars.add("NIGHTLANE_READBACK_REUSE=" +
            (cfg.optBoolean("nightlane_readback_reuse", true) ? "1" : "0"));
        int pipelines = cfg.optInt("nightlane_pipeline_workers", 2);
        vars.add("NIGHTLANE_PIPELINE_WORKERS=" +
            (pipelines == 1 || pipelines == 4 ? pipelines : 2));
        if (cfg.optBoolean("gpu_software_clip", false))
            vars.add("MUGUET_GPU_SOFTWARE_CLIP=1");
        if (cfg.optBoolean("gpu_cpu_bc", false))
            vars.add("MUGUET_GPU_CPU_BC=1");
        // 30 FPS is handled by the game's own -frameLimit=2 mode. Repeating
        // the same 30 Hz limit in the Vulkan thread creates two independent
        // clocks and can make frame delivery uneven during cinematics.
        // Intermediate/lower targets (24, 42, etc.) use the renderer pacer
        // while the engine stays in its 60 Hz base mode. Thermal protection
        // remains independently able to reduce the renderer rate.
        if (fps > 0 && fps < 60 && fps != 30)
            vars.add("MUGUET_RENDER_FPS_LIMIT=" + fps);
        return vars.toArray(new String[0]);
    }

    // Called from the native engine thread via the log bridge. Never touch views
    // directly here; setStatus marshals to the UI thread itself.
    public void setStatus(final String text) {
        statusMessage = text;
        // Graphics-ring status messages are common tracing, not errors.
        // Preserve them in the native log, but never cover gameplay with
        // an oversized center-screen message.
        final boolean serious = EngineStatusPolicy.showInGame(text);
        runOnUiThread(() -> {
            if (status == null) return;
            if (serious) {
                status.setText(text);
                status.setVisibility(View.VISIBLE);
            } else if (status.getVisibility() == View.VISIBLE) {
                status.setVisibility(View.GONE);
            }
        });
    }

    // --- audio ---------------------------------------------------------------
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startAudio() {
        if (audioThreadRunning || audioThread != null) return;
        final int rate = 48000;
        final int channels = AudioFormat.CHANNEL_OUT_STEREO;
        final int encoding = AudioFormat.ENCODING_PCM_16BIT;
        final int min = AudioTrack.getMinBufferSize(rate, channels, encoding);
        if (min <= 0) {
            Log.e("NightlaneAudio", "AudioTrack rejected 48 kHz stereo PCM16, min=" + min);
            return;
        }
        // 8192 frames = 171 ms output capacity. A slightly larger queue
        // protects the Android mixer when Vulkan/wasm2c compilation stalls.
        // The audio worker submits 1024 frames at a time to avoid bursts.
        final int bufferBytes = Math.max(min, 8192 * 2 * 2);
        try {
            audio = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(encoding).setSampleRate(rate)
                    .setChannelMask(channels).build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            if (audio.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("AudioTrack failed to initialize");
            audioPaused = false;
            audioStarted = false;
            audioThreadRunning = true;
        } catch (Throwable error) {
            Log.e("NightlaneAudio", "Audio output initialization failed", error);
            if (audio != null) { audio.release(); audio = null; }
            audioThreadRunning = false;
            return;
        }
        final AudioTrack track = audio;
        audioThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            final short[] block = new short[NativeAudioBufferPolicy.BLOCK_FRAMES * 2];
            long writtenFrames = 0;
            long lastHead32 = 0, headWraps = 0;
            long shortWrites = 0, emptyReads = 0, waitingCycles = 0;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            long lastReport = startedAt;
            long lastStartupReport = startedAt;
            boolean firstPullReported = false;
            boolean firstWriteReported = false;
            long lastProduced = nativeAudioProducedFrames() & 0xffffffffL;
            long lastWrittenFrames = 0;
            // A ten-second mean hides severe one-second audio starvation,
            // particularly during mission transitions and cutscenes.
            long oneSecondAt = startedAt;
            long oneSecondProduced = lastProduced;
            long worstSecondProducedFps = Long.MAX_VALUE;
            int sourceSlowSeconds = 0;
            int sourceSampleSeconds = 0;
            long peakQueue = 0;
            while (audioThreadRunning && !Thread.currentThread().isInterrupted()) {
                if (audioPaused) {
                    try { Thread.sleep(8); }
                    catch (InterruptedException stop) { return; }
                    continue;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                long state;
                try { state = nativeAudioBufferState(); }
                catch (Throwable failure) {
                    Log.e("NightlaneAudio", "Native mixer fill query failed", failure);
                    return;
                }
                int available = NativeAudioBufferPolicy.boundedAvailable(state);
                int capacity = NativeAudioBufferPolicy.ringCapacity(state);
                // Priming previously had no telemetry when the mixer never
                // produced a frame; record this condition in the exported log.
                if (!audioStarted && available == 0 && now - lastStartupReport >= 5000) {
                    String startup = "AudioTrack waiting for native mixer: ringAvailable=0/" + capacity
                        + " producedFrames=" + (nativeAudioProducedFrames() & 0xffffffffL)
                        + " queuedFrames=" + writtenFrames
                        + " workletCallbacks=" + nativeAudioCallbackCount();
                    Log.w("NightlaneAudio", startup);
                    try { nativeAudioReport(startup); }
                    catch (Throwable ignored) {}
                    lastStartupReport = now;
                }
                if (now - oneSecondAt >= 1000) {
                    long producedAt = nativeAudioProducedFrames() & 0xffffffffL;
                    long newFrames = (producedAt - oneSecondProduced) & 0xffffffffL;
                    long secondsElapsedMs = Math.max(1L, now - oneSecondAt);
                    long producedFramesPerSecond = newFrames * 1000L / secondsElapsedMs;
                    // Ignore startup/prebuffering when no audio playback has
                    // begun. A low rate while running indicates a real mixer
                    // shortage rather than an Android AudioTrack write issue.
                    if (audioStarted) {
                        worstSecondProducedFps = Math.min(worstSecondProducedFps, producedFramesPerSecond);
                        sourceSampleSeconds++;
                        if (producedFramesPerSecond < 46000) sourceSlowSeconds++;
                    }
                    oneSecondAt = now;
                    oneSecondProduced = producedAt;
                }
                boolean priming = !audioStarted;
                long played = 0;
                if (!priming) {
                    long head32 = track.getPlaybackHeadPosition() & 0xffffffffL;
                    if (head32 < lastHead32 && lastHead32 - head32 > (1L << 31))
                        headWraps += (1L << 32);
                    lastHead32 = head32;
                    played = headWraps + head32;
                }
                long queued = NativeAudioBufferPolicy.queueDepth(writtenFrames, played);
                peakQueue = Math.max(peakQueue, queued);
                if (priming && now - startedAt < NativeAudioBufferPolicy.MAX_STARTUP_WAIT_MS
                        && available < NativeAudioBufferPolicy.startupWatermark(capacity)) {
                    waitingCycles++;
                    try { Thread.sleep(5); }
                    catch (InterruptedException stop) { return; }
                    continue;
                }
                if (!priming && queued >= NativeAudioBufferPolicy.TARGET_QUEUED_FRAMES) {
                    try { Thread.sleep(4); }
                    catch (InterruptedException stop) { return; }
                    continue;
                }
                if (NativeAudioBufferPolicy.waitForProducer(available, queued, priming)
                        && (priming ? now - startedAt < NativeAudioBufferPolicy.MAX_STARTUP_WAIT_MS
                                   : queued >= NativeAudioBufferPolicy.MIN_QUEUED_FRAMES)) {
                    waitingCycles++;
                    try { Thread.sleep(4); }
                    catch (InterruptedException stop) { return; }
                    continue;
                }
                if (priming && NativeAudioBufferPolicy.waitForInitialSamples(
                        available, capacity, now - startedAt)) {
                    // Do not touch a ring that hasn't been published yet.
                    try { Thread.sleep(10); }
                    catch (InterruptedException stop) { return; }
                    continue;
                }
                if (priming && NativeAudioBufferPolicy.shouldStartAudioTrack(
                        available, capacity, now - startedAt)) {
                    // Crucial: AudioTrack.MODE_STREAM WRITE_BLOCKING can stall
                    // while trying to prefill an output that is not PLAYING.
                    // Previously track.play() was called only after 2048 frames
                    // had been written, which may never happen on some HALs.
                    // Start the consumer before the first write, after the
                    // bounded producer grace period (or a full live prebuffer).
                    try {
                        track.play();
                        audioStarted = true;
                    } catch (IllegalStateException failure) {
                        Log.e("NightlaneAudio", "Unable to start AudioTrack before PCM writes", failure);
                        String message = "AudioTrack.play() failed: " + failure;
                        try { nativeAudioReport(message); } catch (Throwable ignored) {}
                        audioThreadRunning = false;
                        return;
                    }
                    String message = "AudioTrack.play() before first PCM write at "
                        + (now - startedAt) + "ms, mixerAvailable=" + available
                        + "/" + capacity + " workletCallbacks=" + nativeAudioCallbackCount()
                        + " platformBufferFrames=" + track.getBufferCapacityInFrames();
                    Log.i("NightlaneAudio", message);
                    try { nativeAudioReport(message); } catch (Throwable ignored) {}
                }
                // Avoid injecting 1024 zero frames whenever the producer only
                // has a partial block. Consume valid samples, retain remaining
                // output latency and let the producer catch up.
                int request = available > 0 ? Math.min(NativeAudioBufferPolicy.BLOCK_FRAMES, available)
                    : Math.min(256, NativeAudioBufferPolicy.BLOCK_FRAMES);
                int frames;
                try { frames = nativeAudio(block, request); }
                catch (Throwable failure) {
                    Log.e("NightlaneAudio", "Native audio pull failed", failure);
                    return;
                }
                if (!firstPullReported) {
                    firstPullReported = true;
                    String message = "AudioTrack first native PCM pull: requested=" + request
                        + " returned=" + frames
                        + " mixerProduced=" + (nativeAudioProducedFrames() & 0xffffffffL)
                        + " workletCallbacks=" + nativeAudioCallbackCount();
                    Log.i("NightlaneAudio", message);
                    try { nativeAudioReport(message); } catch (Throwable ignored) {}
                }
                if (frames <= 0) {
                    emptyReads++;
                    try { Thread.sleep(5); }
                    catch (InterruptedException stop) { return; }
                    continue;
                }
                int requestedSamples = Math.min(frames * 2, block.length);
                int offset = 0;
                if (!firstWriteReported) {
                    String message = "AudioTrack first PCM WRITE_BLOCKING start: samples="
                        + requestedSamples + " playing=" + audioStarted;
                    try { nativeAudioReport(message); } catch (Throwable ignored) {}
                }
                while (offset < requestedSamples && audioThreadRunning && !audioPaused) {
                    int requested = requestedSamples - offset;
                    int wrote;
                    try {
                        wrote = track.write(block, offset, requested, AudioTrack.WRITE_BLOCKING);
                    } catch (RuntimeException writeError) {
                        Log.e("NightlaneAudio", "AudioTrack blocking write failed", writeError);
                        try { nativeAudioReport("AudioTrack.write failed: " + writeError); }
                        catch (Throwable ignored) {}
                        audioThreadRunning = false;
                        return;
                    }
                    if (wrote > 0) {
                        offset += wrote;
                        if (wrote < requested) shortWrites++;
                        // WRITE_BLOCKING counts samples, not frames.
                        writtenFrames += wrote / 2;
                    } else if (wrote == 0) {
                        shortWrites++;
                        break;
                    } else {
                        String message = "AudioTrack.write returned error " + wrote;
                        Log.w("NightlaneAudio", message);
                        try { nativeAudioReport(message); } catch (Throwable ignored) {}
                        audioThreadRunning = false;
                        break;
                    }
                }
                if (!firstWriteReported) {
                    firstWriteReported = true;
                    String message = "AudioTrack first PCM write finished: samples="
                        + offset + "/" + requestedSamples + " playing=" + audioStarted;
                    Log.i("NightlaneAudio", message);
                    try { nativeAudioReport(message); } catch (Throwable ignored) {}
                }
                if (now - lastReport >= 10000) {
                    long produced = nativeAudioProducedFrames() & 0xffffffffL;
                    long newFrames = (produced - lastProduced) & 0xffffffffL;
                    double elapsed = Math.max(0.001, (now - lastReport) / 1000.0);
                    String report = "gameProducedFps=" + Math.round(newFrames / elapsed)
                        + " outputSubmittedFps=" + Math.round((writtenFrames - lastWrittenFrames) / elapsed)
                        + " ringAvailable=" + available + "/" + capacity
                        + " workletCallbacks=" + nativeAudioCallbackCount()
                        + " outputQueued=" + queued + " peakQueue=" + peakQueue
                        + " audioState=" + track.getPlayState()
                        + " AndroidUnderruns=" + track.getUnderrunCount()
                        + " shortWrites=" + shortWrites + " emptyReads=" + emptyReads
                        + " producerWaits=" + waitingCycles
                        + " min1sProducedFps=" + (sourceSampleSeconds == 0 ? -1 : worstSecondProducedFps)
                        + " slowProducerSeconds=" + sourceSlowSeconds + "/" + sourceSampleSeconds
                        + " (needs 48000 stereo frames/s)";
                    Log.i("NightlaneAudio", report);
                    try { nativeAudioReport(report); }
                    catch (Throwable loggingError) {
                        Log.w("NightlaneAudio", "Could not save audio telemetry", loggingError);
                    }
                    lastReport = now;
                    lastProduced = produced;
                    lastWrittenFrames = writtenFrames;
                    peakQueue = 0;
                    worstSecondProducedFps = Long.MAX_VALUE;
                    sourceSlowSeconds = sourceSampleSeconds = 0;
                    shortWrites = emptyReads = waitingCycles = 0;
                }
            }
        }, "nightlane-audio");
        audioThread.start();
    }

    private void stopAudio() {
        audioThreadRunning = false;
        audioStarted = false;
        if (audioThread != null) audioThread.interrupt();
        if (audio != null) {
            try { audio.pause(); audio.flush(); audio.stop(); }
            catch (IllegalStateException ignored) {}
        }
        if (audioThread != null) {
            try { audioThread.join(200); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            audioThread = null;
        }
        if (audio != null) {
            audio.release();
            audio = null;
        }
    }

    // --- input ---------------------------------------------------------------
    private void clearInput() {
        keyboardKeys.clear(); padKeys.clear();
        lx = ly = rx = ry = lt = rt = 0;
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
            // Sample the virtual right stick every UI tick even when the
            // finger is stationary. Lift immediately centers the stick.
            nativeUpdatePad(keys.toArray(new String[0]), touchButtons, horn,
                            touch.rightStickX, touch.rightStickY, true);

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

    // Independently observes the native mixer and Android playback clock.
    // Unlike the audio worker's 10-second report, this still runs when the
    // audio worker is blocked inside a native call or a PCM write.
    private void recordAudioWatchdog() {
        long nowMs = android.os.SystemClock.elapsedRealtime();
        if (lastAudioWatchdogAtMs != 0 && nowMs - lastAudioWatchdogAtMs < 5000) return;
        try {
            long ring = nativeAudioBufferState();
            long produced = nativeAudioProducedFrames() & 0xffffffffL;
            long callbacks = nativeAudioCallbackCount() & 0xffffffffL;
            int available = NativeAudioBufferPolicy.boundedAvailable(ring);
            int capacity = NativeAudioBufferPolicy.ringCapacity(ring);
            AudioTrack track = audio;
            Thread worker = audioThread;
            long played = track == null ? -1L
                : (track.getPlaybackHeadPosition() & 0xffffffffL);
            long elapsed = lastAudioWatchdogAtMs == 0 ? 0 : nowMs - lastAudioWatchdogAtMs;
            long producedDelta = elapsed == 0 ? -1
                : (produced - lastAudioWatchdogProduced) & 0xffffffffL;
            long callbacksDelta = elapsed == 0 ? -1
                : (callbacks - lastAudioWatchdogCallbacks) & 0xffffffffL;
            long playedDelta = elapsed == 0 || played < 0 || lastAudioWatchdogPlayback < 0 ? -1
                : (played - lastAudioWatchdogPlayback) & 0xffffffffL;
            String workerState = worker == null ? "null" : worker.getState().toString();
            String stackTop = "";
            if (worker != null && producedDelta == 0) {
                StackTraceElement[] stack = worker.getStackTrace();
                if (stack.length > 0) stackTop = " workerTop=" + stack[0].toString();
            }
            String message = "audio-watchdog: producer=" + produced
                + " delta=" + producedDelta + "/" + elapsed + "ms"
                + " copiedCallbacks=" + callbacks + " delta=" + callbacksDelta
                + " ring=" + available + "/" + capacity
                + " audioStarted=" + audioStarted + " paused=" + audioPaused
                + " trackState=" + (track == null ? -1 : track.getPlayState())
                + " headFrames=" + played + " headDelta=" + playedDelta
                + " underruns=" + (track == null ? -1 : track.getUnderrunCount())
                + " worker=" + workerState + stackTop;
            Log.i("NightlaneAudio", message);
            nativeAudioReport(message);
            lastAudioWatchdogAtMs = nowMs;
            lastAudioWatchdogProduced = produced;
            lastAudioWatchdogCallbacks = callbacks;
            lastAudioWatchdogPlayback = played;
        } catch (Throwable error) {
            Log.e("NightlaneAudio", "Audio watchdog failed", error);
        }
    }

    private void updateFps() {
        long now = System.nanoTime();
        // Do not call JNI or update TextView until a full second has elapsed.
        if (fpsAt != 0 && now - fpsAt < 1_000_000_000L) return;
        recordAudioWatchdog();
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
        if (audio != null && audioPaused) {
            audioPaused = false;
            if (audioStarted) {
                try { audio.play(); }
                catch (IllegalStateException error) { Log.e("NightlaneAudio", "Resume playback failed", error); }
            }
        }
    }
    @Override protected void onPause() {
        running = false; handler.removeCallbacks(tick);
        if (engineCoreLoaded) { devices.stop(); clearInput(); }
        audioPaused = true;
        if (audio != null) {
            // Keep the bounded queue across a short pause. Flushing resets
            // playback accounting and can make the pump think it is forever full.
            try { audio.pause(); } catch (IllegalStateException ignored) {}
        }
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
        stopAudio();
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