package dev.muguet;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LauncherActivity extends Activity {
    private static final int PICK_ZIP = 1;
    private static final int PICK_MAGNET = 2;
    private static final int PICK_FOLDER = 3;
    private static final int PICK_SAVE_ZIP = 4;
    private static final int PICK_SAVE_LOG = 6;
    private static final int PICK_DRIVER_ZIP = 7;
    private static final String DEFAULT_MAGNET =
        "magnet:?xt=urn:btih:60a2d95f23154e4f7c75a965307133c24d9c7178&dn=GrandV-assets"
        + "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451"
        + "&tr=http%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
        + "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.qu.ax%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.skynetcloud.site%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.qu.ax%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.ducks.party%3A1984%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.tryhackx.org%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.corpscorp.online%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.bittor.pw%3A1337%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker-udp.gbitt.info%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.nyaa.vc%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.gmi.gd%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker2.dler.org%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.dler.org%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker2.dler.org%3A80%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.dler.org%3A80%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.dler.com%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.renfei.net%3A8080%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.farted.net%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.peerfect.org%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.opentrackr.com%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.ilibr.org%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Fmail.segso.net%3A6969%2Fannounce"
        + "&tr=https%3A%2F%2Ftracker.pmman.tech%3A443%2Fannounce"
        + "&tr=https%3A%2F%2Fht.therarbg.to%3A443%2Fannounce"
        + "&tr=http%3A%2F%2Fbittorrent-tracker.e-n-c-r-y-p-t.net%3A1337%2Fannounce"
        + "&tr=udp%3A%2F%2Fretracker01-msk-virt.corbina.net%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Ft.overflow.biz%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Ft.overflow.biz%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.004430.xyz%3A1337%2Fannounce"
        + "&tr=https%3A%2F%2Ftracker.nekomi.cn%3A443%2Fannounce"
        + "&tr=https%3A%2F%2F004430.xyz%3A443%2Fannounce"
        + "&tr=http%3A%2F%2F004430.xyz%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Fipv4announce.sktorrent.eu%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Fipv4announce.sktorrent.eu%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Fevan.im%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftr4ck3r.duckdns.org%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Fns575949.ip-51-222-82.net%3A6969%2Fannounce"
        + "&tr=https%3A%2F%2Ftr.nyacat.pw%3A443%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.mywaifu.best%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Ftr.nyacat.pw%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.wildkat.net%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Fopen.ftorrent.com%3A443%2Fannounce"
        + "&tr=udp%3A%2F%2Fmartin-gebhardt.eu%3A25%2Fannounce"
        + "&tr=https%3A%2F%2Ftracker.7471.top%3A443%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.waaa.moe%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.dhitechnical.com%3A6969%2Fannounce"
        + "&tr=http%3A%2F%2Fopen.tracker.cl%3A1337%2Fannounce"
        + "&tr=udp%3A%2F%2Fadmin.52ywp.com%3A6969%2Fannounce"
        + "&tr=https%3A%2F%2Ftracker.zhuqiy.com%3A443%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.aruku.ovh%3A8081%2Fannounce"
        + "&tr=https%3A%2F%2Ftracker.foreverpirates.co%3A443%2Fannounce"
        + "&tr=https%3A%2F%2Fopen.ftorrent.com%3A443%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.torrents.observer%3A80%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.nyaa.net%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.k.vu%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker.cn.nyaa.net%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Fkolankoalastree.newtrackon.co.nz%3A1337%2Fannounce"
        + "&tr=http%3A%2F%2Ftracker.zhuqiy.dgj055.icu%3A80%2Fannounce"
        + "&tr=https%3A%2F%2Ft.213891.xyz%3A443%2Fannounce"
        + "&tr=http%3A%2F%2F1337.abcvg.info%3A80%2Fannounce";
    // Every graphics knob the web port exposes, in the same order, with the same
    // choices, taken from graphics_settings.js on the game data. Columns are
    // label, engine flag, then the engine's own values. DISPLAY below renames
    // those values for people who do not know what a LOD bias is; index N of a
    // DISPLAY row is the same knob as GRAPHICS[i][2 + N].
    private static final String[][] GRAPHICS = {
        {"Frame limit", "-frameLimit", "auto", "24", "30", "42", "60"},
        {"Texture quality", "-textureQuality", "auto", "0", "1", "2"},
        {"Reflection quality (mirrors, glass)", "-reflectionQuality", "auto", "0", "1", "2", "3"},
        {"Water quality", "-waterQuality", "auto", "0", "1"},
        {"Particle quality (smoke, sparks)", "-particleQuality", "auto", "0", "1", "2"},
        {"Grass quality", "-grassQuality", "auto", "0", "1", "2", "3"},
        {"Texture sharpness at angles", "-anisotropicQualityLevel", "auto", "0", "2", "4", "8", "16"},
        {"Scenery detail at a distance", "-lodScale", "auto", "0", "0.25", "0.5", "0.75", "1"},
        {"Pedestrian detail at a distance", "-pedLodBias", "auto", "0", "0.25", "0.5", "0.75", "1"},
        {"Vehicle detail at a distance", "-vehicleLodBias", "auto", "0", "0.25", "0.5", "0.75", "1"},
        {"City density (crowds, traffic)", "-cityDensity", "auto", "0", "0.25", "0.5", "0.75", "1"},
        {"Pedestrian variety", "-pedVariety", "auto", "0", "0.25", "0.5", "0.75", "1"},
        {"Vehicle variety", "-vehicleVariety", "auto", "0", "0.25", "0.5", "0.75", "1"},
        {"Fullscreen effects (bloom, grade)", "-postFX", "auto", "0", "1", "2", "3"},
        {"Motion blur", "-MotionBlurStrength", "auto", "0", "0.25", "0.5", "1"},
        {"Depth of field (blurred background)", "-noInGameDOF", "auto", "1", "0"},
        {"Shadow quality", "-shadowQuality", "auto", "-1", "0", "1", "2"},
        {"Shadow edges", "-shadowSoftness", "auto", "0", "1", "2", "3", "4", "5"},
        {"Ambient occlusion (corners and creases)", "-SSAO", "auto", "0", "1", "2"},
        {"Render distance (how far you can see)", "-farclip", "auto", "10", "25", "50", "100", "200", "500", "1000", "2000", "5000"},
        {"Fog you can walk through", "-fogVolumes", "auto", "0", "1"},
        {"Shadows from smoke and fire", "-particleShadows", "auto", "0", "1"},
        {"Long shadows from the sun", "-shadowLongShadows", "auto", "0", "1"},
        {"Loading assets ahead of you", "-HDStreamingInFlight", "auto", "0", "1"},
        {"Lights on far-away buildings", "-nolodlights", "auto", "on", "off"},
    };
    private static final String[][] DISPLAY = {
        {"Automatic", "24 fps", "30 fps", "42 fps", "60 fps"},
        {"Automatic", "Very low", "Low", "High"},
        {"Automatic", "Off", "Low", "Medium", "High"},
        {"Automatic", "Off", "High"},
        {"Automatic", "Off", "Low", "Medium", "High"},
        {"Automatic", "Off", "Very low", "Low", "High"},
        {"Automatic", "Off", "2x", "4x", "8x", "16x"},
        {"Automatic", "Lowest (fastest)", "Low", "Medium", "High", "Full"},
        {"Automatic", "Lowest (fastest)", "Low", "Medium", "High", "Full"},
        {"Automatic", "Lowest (fastest)", "Low", "Medium", "High", "Full"},
        {"Automatic", "Empty", "Quiet", "Medium", "Busy", "Full"},
        {"Automatic", "None", "Low", "Medium", "High", "Full"},
        {"Automatic", "None", "Low", "Medium", "High", "Full"},
        {"Automatic", "Off", "Low", "Medium", "High"},
        {"Automatic", "Off", "Light", "Medium", "Full"},
        {"Automatic", "Off", "On"},
        {"Automatic", "None at all", "Off (fewer)", "Low", "High"},
        {"Automatic", "Hard edges", "Hard", "Normal", "Soft", "Softer", "Softest"},
        {"Automatic", "Off", "Low", "High"},
        {"Automatic", "Extreme close", "Very close", "Close", "Near", "Short", "Medium", "Long", "Very long", "Full"},
        {"Automatic", "Off", "On"},
        {"Automatic", "Off", "On"},
        {"Automatic", "Off", "On"},
        {"Automatic", "Off", "On"},
        {"Automatic", "On", "Off"},
    };
    // Same three presets as the web launcher. "Low" is the phone default: 30 fps,
    // everything cheap, shadows gone entirely and the render distance at 100 m,
    // which is what a mid-range phone needs. Depth of field is inverted to match
    // the engine's own reading, and shadow quality uses -1, the value
    // graphics_settings.js passes when the web launcher turns shadows off.
    // Every row must have one entry per GRAPHICS row, in the same order.
    private static final String[][] PRESETS = {
        {"30", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0.25", "0", "0", "0", "0", "1", "-1", "0", "0", "100", "0", "0", "0", "0", "off"},
        {"auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto", "auto"},
        {"60", "2", "3", "1", "2", "3", "16", "1", "1", "1", "1", "1", "1", "3", "1", "0", "2", "3", "2", "2000", "1", "1", "1", "1", "on"},
    };
    private Spinner[] graphicsSpinners;
    private Spinner graphicsPreset;
    private boolean loadingGraphics;

    private static native boolean nativePrepare(String directory);
    private static native boolean nativeShaderValidate(String wgslSource);
    private native void nativeImport(String archive);
    // Returns the folder inside the picked one that holds the game (data/ and
    // b/), or null when there is none.
    private native String nativeGameRoot(String path);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // The native library is around 18 MB and takes roughly a second to load, so it
    // is loaded on the worker instead of a static initializer: the launcher can draw
    // immediately, and every native call waits for the load to finish. The worker is
    // single-threaded, so a later import is still queued behind this one.
    private final AtomicBoolean loadingNative = new AtomicBoolean();
    private final AtomicBoolean warmingNative = new AtomicBoolean();
    private final AtomicBoolean nativeLoaded = new AtomicBoolean();
    private final AtomicReference<Exception> nativeFailure = new AtomicReference<>();
    private boolean firstResume = true;
    private TextView status;
    private TextView savesStatus;
    private Button play, playEngine, importButton, clearFolderButton, grantAccessButton, downloadGameButton;
    private TextView folderStatus;
    private ProgressBar progress;
    private Spinner mode, heap, streamingBudget, worldstreamMode, msaaMode, frameQueue, nativeCpuWorkers, pipelineWorkers;
    private Spinner nightlanePresent, nightlaneSharpness, nightlaneLod, nightlaneHudSize, nightlaneHudOpacity;
    private CheckBox nightlaneThermal, nightlaneCompactHud, touchFreeAim, touchpadCamera, automaticHeap, readAhead, readbackReuse, prelaunchWarmup, shadowFix;
    private static final double[] STREAMING_BUDGETS = {0.35, 0.25, 0.15};
    private EditText scale, name, magnetField, relayUrl, relayRoom, relayKey;
    private CheckBox mute, multiplayer, cpuBc, softwareClip;
    private SeekBar sensitivity;
    private TextView sensitivityLabel;
    private JSONObject settings;
    private boolean importing;
    private final AtomicBoolean cancelDownload = new AtomicBoolean();
    private boolean downloading;
    private Button pauseDownload;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int stroke) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private void styleLauncherChild(android.view.View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(9));
        child.setLayoutParams(params);
        if (child instanceof Button) {
            Button button = (Button) child;
            button.setAllCaps(false);
            button.setTextColor(0xFFF3F7FF);
            button.setTextSize(15);
            button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            button.setBackground(rounded(0xFF1C2A3D, 0xFF344760));
            button.setPadding(dp(16), dp(13), dp(16), dp(13));
            button.setMinHeight(dp(52));
            button.setElevation(dp(2));
        } else if (child instanceof EditText) {
            EditText input = (EditText) child;
            input.setTextColor(0xFFF3F7FF);
            input.setHintTextColor(0xFF91A0B5);
            input.setBackground(rounded(0xFF182335, 0xFF344760));
            input.setPadding(dp(14), dp(13), dp(14), dp(13));
        } else if (child instanceof TextView) {
            TextView text = (TextView) child;
            if (text.getCurrentTextColor() != 0xFF70D8FF) text.setTextColor(0xFFD5E0EF);
            text.setLineSpacing(dp(2), 1f);
        } else if (child instanceof Spinner) {
            child.setBackground(rounded(0xFF1C2A3D, 0xFF344760));
            child.setPadding(dp(10), dp(8), dp(10), dp(8));
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        scroll.addView(column);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0B101A);
        column.setPadding(dp(20), dp(24), dp(20), dp(36));
        column.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(android.view.View parent, android.view.View child) {
                styleLauncherChild(child);
            }
            @Override public void onChildViewRemoved(android.view.View parent, android.view.View child) {}
        });
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("NIGHTLANE");
        title.setTextSize(32);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        column.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("AI-ASSISTED FORK  /  ANDROID");
        subtitle.setTextSize(12);
        subtitle.setLetterSpacing(0.15f);
        subtitle.setTextColor(0xFF70D8FF);
        column.addView(subtitle);
        TextView warning = new TextView(this);
        warning.setText("ARM64 / Android 10+ / Vulkan. A high-end phone with 8–12 GB RAM is recommended, not a guarantee of compatibility. Supply your compatible game data via import or the HTTPS installer. Native-only builds use compiled ARM64 game code and shader resources; the old game.wasm is not needed on the phone. A normal PC installation is not enough. This test APK uses compiled native ARM64 game code; playability is not yet verified.");
        column.addView(warning);
        status = new TextView(this);
        column.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        column.addView(progress);
        // Game data comes from the user's own files: either a ZIP to extract or a folder
        // to use where it lies. The app never fetches game data from any server,
        // which keeps this project clear of mirroring Rockstar's content.
        importButton = new Button(this);
        importButton.setText("Import game ZIP");
        importButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_ZIP);
        });
        column.addView(importButton);
        Button driverImport = new Button(this);
        driverImport.setText("Import Vulkan driver ZIP (experimental)");
        driverImport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            startActivityForResult(intent, PICK_DRIVER_ZIP);
        });
        column.addView(driverImport);
        Button driverChoice = new Button(this);
        driverChoice.setText("Vulkan driver: " + DriverPackages.selected(this));
        driverChoice.setOnClickListener(v -> {
            java.util.ArrayList<String> choices = new java.util.ArrayList<>();
            choices.add("system");
            File[] packages = DriverPackages.root(this).listFiles();
            if (packages != null) for (File file : packages) {
                if (file.isDirectory() && file.getName().matches("[A-Za-z0-9_-]{1,64}"))
                    choices.add("custom:" + file.getName());
            }
            new AlertDialog.Builder(this).setTitle("Requested Vulkan driver")
                .setItems(choices.toArray(new String[0]), (dialog, index) -> {
                    try {
                        DriverPackages.select(this, choices.get(index));
                        driverChoice.setText("Vulkan driver: " + choices.get(index));
                        status.setText("Driver choice saved. Custom driver activation is not wired to Vulkan yet; system driver remains active.");
                    } catch (IOException e) { status.setText("Cannot save driver choice: " + e.getMessage()); }
                }).show();
        });
        column.addView(driverChoice);

        // Not everyone has the game data as a ZIP. A folder that already holds
        // data/ and b/<build>/ works just as well, and is used where it lies, so
        // nothing is copied and the phone needs no free space for it.
        Button folderButton = new Button(this);
        folderButton.setText("Use a game files folder");
        folderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_FOLDER);
        });
        column.addView(folderButton);
        folderStatus = new TextView(this);
        column.addView(folderStatus);
        clearFolderButton = new Button(this);
        clearFolderButton.setText("Go back to the imported copy");
        clearFolderButton.setOnClickListener(v -> { settings.remove("game_folder_uri"); settings.remove("game_folder_path"); saveSettings(); ready(); });
        column.addView(clearFolderButton);
        // A picked /sdcard folder can be stat-ed but not opened while another app
        // owns the files, and a SAF grant does not change that. All-files access
        // does, and it is the user's decision, not ours.
        grantAccessButton = new Button(this);
        grantAccessButton.setText("Grant file access");
        grantAccessButton.setVisibility(android.view.View.GONE);
        grantAccessButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { status.setText("Grant 'All files access' to Nightlane Android in Settings > Apps > Special access."); }
        });
        column.addView(grantAccessButton);
        // Native-only wasm2c writes to game/userdata and game/sandbox, while
        // the older Wasmtime path used app/files/saves. Back up BOTH.
        TextView savesHeader = new TextView(this);
        savesHeader.setText("Saves — back up before reinstalling or changing game folders");
        column.addView(savesHeader);
        savesStatus = new TextView(this);
        column.addView(savesStatus);
        Button backupButton = new Button(this);
        backupButton.setText("Back up native and legacy saves");
        backupButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "gta5-saves.zip");
            startActivityForResult(intent, PICK_SAVE_ZIP);
        });
        column.addView(backupButton);
        Button restoreButton = new Button(this);
        restoreButton.setText("Restore saves from a ZIP");
        restoreButton.setOnClickListener(v -> {
            // Overwriting the live save is not undoable, so say what it costs first.
            new AlertDialog.Builder(this)
                .setTitle("Replace your saves?")
                .setMessage("Everything currently saved on this phone is replaced by the ZIP's contents. This cannot be undone, so back up first if you are unsure.")
                .setPositiveButton("Replace", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, PICK_SAVE_ZIP + 1);
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        column.addView(restoreButton);
        Button exportLogsButton = new Button(this);
        exportLogsButton.setText("Export logs");
        exportLogsButton.setOnClickListener(v -> {
            File log = logFile();
            File nativeLog = nativeLogFile();
            boolean hasNormal = log.isFile() && log.length() > 0;
            boolean hasNative = nativeLog.isFile() && nativeLog.length() > 0;
            if (!hasNormal && !hasNative) {
                status.setText("No logs yet. Launch the game once, then export.");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "gta5-logs.txt");
            startActivityForResult(intent, PICK_SAVE_LOG);
        });
        column.addView(exportLogsButton);
        updateSavesStatus();
        try { settings = new JSONObject(new String(Files.readAllBytes(config().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { settings = new JSONObject(); }
        // The user pastes their own magnet link; we only hand it to whatever
        // torrent app they already have. Nothing is fetched or hosted in-app.
        magnetField = field(column, "Game torrent (paste your own magnet link)", settings.optString("last_magnet", DEFAULT_MAGNET));
        Button torrentButton = new Button(this);
        torrentButton.setText("Open torrent client");
        torrentButton.setOnClickListener(v -> {
            String link = magnetField.getText().toString().trim();
            if (!link.startsWith("magnet:?")) {
                status.setText("Paste a magnet:? link first.");
                return;
            }
            try {
                settings.put("last_magnet", link);
                Intent torrent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                torrent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(torrent);
            } catch (Exception e) {
                status.setText("No torrent app found. Install qBittorrent or Flud first.");
            }
        });
        column.addView(torrentButton);
        // Long magnets are painful to type on a phone. Pick a text file from
        // anywhere (Downloads, Drive) instead; the link itself never ships here.
        Button loadMagnetButton = new Button(this);
        loadMagnetButton.setText("Load magnet from file");
        loadMagnetButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_MAGNET);
        });
        column.addView(loadMagnetButton);
        mode = choice(column, "Start mode", new String[]{"Story", "Sandbox"}, settings.optInt("start_mode", 2) == 1 ? 0 : 1);
        heap = choice(column, "Maximum game heap (not total app memory)", new String[]{"3 GB", "4 GB", "5 GB", "6 GB", "7 GB", "8 GB"}, Math.max(0, Math.min(5, settings.optInt("android_heap_gb", 5) - 3)));
        int workers = settings.optInt("native_cpu_workers", 2);
        nativeCpuWorkers = choice(column, "Native engine CPU workers (benchmark 2 vs 4 vs 6)",
            new String[]{"2 workers — baseline", "4 workers — experimental", "6 workers — experimental"},
            workers >= 6 ? 2 : workers >= 4 ? 1 : 0);
        int shaderWorkers = settings.optInt("nightlane_pipeline_workers", 2);
        pipelineWorkers = choice(column, "Vulkan shader compiler workers (lower CPU contention)",
            new String[]{"1 worker", "2 workers — mobile default", "4 workers — fast initial compilation"},
            shaderWorkers >= 4 ? 2 : shaderWorkers <= 1 ? 0 : 1);
        automaticHeap = check(column, "Reserve memory for Android and graphics (limit heap to physical RAM budget)",
            settings.optBoolean("android_auto_heap", true));
        readAhead = check(column, "Experimental storage read-ahead (may reduce streaming stalls; test on your phone)",
            settings.optBoolean("nightlane_read_ahead", true));
        readbackReuse = check(column, "Reuse completed Vulkan readback buffers (experimental; safe off switch)",
            settings.optBoolean("nightlane_readback_reuse", true));
        prelaunchWarmup = check(column, "Prepare shaders and pre-cache nearby game assets before Play (progress bar; skippable)",
            settings.optBoolean("nightlane_prelaunch_warmup", true));
        String queueMode = settings.optString("nightlane_frame_queue", "low_latency");
        frameQueue = choice(column, "Frame queue (restart game to apply)",
            new String[]{"Original — 3 frames", "Lower latency — 2 frames", "Minimum latency — 1 frame (may reduce FPS)"},
            "minimal".equals(queueMode) ? 2 : "low_latency".equals(queueMode) ? 1 : 0);
        shadowFix = check(column, "Shadow cascade fix (integer selection and stable receiver bias)",
            settings.optBoolean("nightlane_shadow_fix", true));
        double currentStreaming = settings.optDouble("streamer_percentage", 0.35);
        int streamingChoice = currentStreaming <= 0.20 ? 2 : currentStreaming <= 0.30 ? 1 : 0;
        streamingBudget = choice(column, "World streaming memory (lower uses less memory but may cause more asset reloads)",
            new String[]{"Balanced — 35% (current)", "Memory saver — 25%", "Aggressive — 15% (experimental)"}, streamingChoice);
        // Graphics live here, not in a save file: the web port's graphics_settings.js
        // says "apply after custom args; never edit SGTA saves", and the engine rewrites
        // its own settings.xml on every save.
        TextView graphicsHeader = new TextView(this);
        graphicsHeader.setText("Graphics — passed to the engine as arguments, the same knobs the web launcher exposes");
        column.addView(graphicsHeader);
        TextView streamHeader = new TextView(this);
        streamHeader.setText("WorldStream — Phase 0 baseline & safety");
        column.addView(streamHeader);
        String savedWorldstreamMode = settings.optString("nightlane_worldstream_mode", "original");
        worldstreamMode = choice(column, "WorldStream backend",
            new String[]{"Original — working game streaming (default)",
                "Experimental — reserved; currently runs Original"},
            "experimental".equalsIgnoreCase(savedWorldstreamMode) ? 1 : 0);
        TextView streamNote = new TextView(this);
        streamNote.setText("Phase 0 only measures the existing streamer. Experimental is a reserved switch: until the new backend passes testing, gameplay still uses Original. Export logs after a repeatable driving run.");
        column.addView(streamNote);
        graphicsPreset = choice(column, "Graphics preset", new String[]{"Low (phone default)", "Engine default", "Max"}, 0);
        graphicsPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (!loadingGraphics) applyPreset(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        graphicsSpinners = new Spinner[GRAPHICS.length];
        loadingGraphics = true;
        for (int i = 0; i < GRAPHICS.length; i++) {
            String[] row = GRAPHICS[i];
            graphicsSpinners[i] = choice(column, row[0], DISPLAY[i], 0);
        }
        restoreGraphics();
        loadingGraphics = false;
        cpuBc = check(column, "Force CPU BC texture decoding (higher RAM use; diagnostics)", settings.optBoolean("gpu_cpu_bc", false));
        softwareClip = check(column, "Force software clip distances (Adreno turns this on by itself)", settings.optBoolean("gpu_software_clip", false));
        scale = field(column, "Native render scale (0.50–1.0; lower may improve GPU FPS)",
            Double.toString(settings.optDouble("nightlane_native_render_scale", 0.75)));
        scale.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        String savedMsaa = settings.optString("android_msaa_mode", "default");
        msaaMode = choice(column, "Legacy anti-aliasing option (native engine currently uses automatic)",
            new String[]{"Up to 2x — current default", "Adaptive — 1x at low scale or on devices with 6 GB RAM or less", "1x — MSAA off"},
            "adaptive".equals(savedMsaa) ? 1 : "off".equals(savedMsaa) ? 2 : 0);
        String presentMode = settings.optString("nightlane_present", "native");
        nightlanePresent = choice(column, "Nightlane graphics reconstruction (experimental)",
            new String[]{"Native — original", "Smooth — less pixelation", "Sharp — adaptive edge/detail boost"},
            "sharp".equalsIgnoreCase(presentMode) ? 2 : "smooth".equalsIgnoreCase(presentMode) ? 1 : 0);
        double selectedSharpness = settings.optDouble("nightlane_sharpness", 0.30);
        nightlaneSharpness = choice(column, "Sharpen strength (applies only to Sharp mode)",
            new String[]{"Gentle", "Balanced", "Strong"}, 
            selectedSharpness >= 0.40 ? 2 : selectedSharpness >= 0.225 ? 1 : 0);
        String lodMode = settings.optString("nightlane_lod", "performance");
        nightlaneLod = choice(column, "Nightlane distant scene detail (may affect FPS)",
            new String[]{"Performance — existing lowest LOD", "Balanced — slightly more distant detail",
                "Detailed — more distant objects (heavier)", "Engine defaults — turn off phone LOD override"},
            "original".equals(lodMode) ? 3 : "detail".equals(lodMode) ? 2 :
                "balanced".equals(lodMode) ? 1 : 0);
        nightlaneThermal = check(column, "Adaptive thermal FPS protection (Severe 42 / Critical 30 / Emergency 24)",
            settings.optBoolean("nightlane_thermal_adaptive", true));
        touchFreeAim = check(column, "Free aiming for touch controls (lets right-thumb swipe steer your aim while holding AIM)",
            settings.optBoolean("nightlane_touch_free_aim", true));
        touchpadCamera = check(column, "Camera: touchpad swipe (mobile style). Uncheck for the old virtual right stick",
            settings.optBoolean("nightlane_touchpad_camera", true));
        nightlaneCompactHud = check(column, "Compact touch HUD — group secondary buttons under MORE",
            settings.optBoolean("nightlane_compact_hud", true));
        double savedHudScale = settings.optDouble("nightlane_hud_scale", 0.85);
        nightlaneHudSize = choice(column, "Touch HUD button size",
            new String[]{"Compact — 85%", "Normal — 100%", "Large — 115%"},
            savedHudScale >= 1.075 ? 2 : savedHudScale >= 0.925 ? 1 : 0);
        double savedHudOpacity = settings.optDouble("nightlane_hud_opacity", 0.16);
        nightlaneHudOpacity = choice(column, "Touch HUD opacity",
            new String[]{"Subtle — 16%", "Normal — 24%", "High contrast — 36%"},
            savedHudOpacity >= 0.30 ? 2 : savedHudOpacity >= 0.20 ? 1 : 0);
        mute = check(column, "Mute audio", settings.optBoolean("mute", false));
        // This plays a short, quiet tone without launching GTA or reading any
        // game mixer samples. It isolates device playback from the native port.
        Button testSpeaker = new Button(this);
        testSpeaker.setText("Test speaker (short tone)");
        testSpeaker.setOnClickListener(v -> {
            testSpeaker.setEnabled(false);
            status.setText("Playing a short Android speaker test tone…");
            AudioOutputProbe.test(result -> runOnUiThread(() -> {
                status.setText(result);
                testSpeaker.setEnabled(true);
            }));
        });
        column.addView(testSpeaker);
        multiplayer = check(column, "Legacy multiplayer setting (native engine offline-only)", settings.optBoolean("multiplayer", false));
        // The relay is run by whoever hosts the session; you need its address, the
        // room name and the access code that host typed. Same rules as the web
        // dialogs: rooms are 1-32 of letters/digits/_/-, codes are 4-128 printable
        // ASCII and case-sensitive, and wss:// is not wired up in this build yet.
        relayUrl = field(column, "Relay address (ws://host:8012/relay)", settings.optString("relay_url", "ws://"));
        relayRoom = field(column, "Room name", settings.optString("relay_room", "friends"));
        relayKey = field(column, "Access code (from the host)", settings.optString("relay_key", ""));
        name = field(column, "Player name", settings.optString("player_name", ""));
        sensitivityLabel = new TextView(this);
        sensitivityLabel.setText("Camera swipe sensitivity (up to 12x)");
        column.addView(sensitivityLabel);
        sensitivity = new SeekBar(this);
        sensitivity.setMax(LookSensitivity.MAX_PROGRESS);
        int savedLook = LookSensitivity.toSlider((float) settings.optDouble("touch_sensitivity", 1.0));
        sensitivity.setProgress(savedLook);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int level = Math.max(1, value);
                if (level != value) bar.setProgress(level); // 0 leaves touch look unusable.
                sensitivityLabel.setText("Camera sensitivity: " + String.format(java.util.Locale.ROOT, "%.2fx", LookSensitivity.fromSlider(level)));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sensitivityLabel.setText("Look sensitivity (touch drag): " + String.format(java.util.Locale.ROOT, "%.2fx", LookSensitivity.fromSlider(sensitivity.getProgress())));
        column.addView(sensitivity);
        play = new Button(this);
        play.setText("Play — native ARM64 (experimental)");
        play.setOnClickListener(v -> playNative());
        pauseDownload = new Button(this);
        pauseDownload.setText("Pause download");
        pauseDownload.setVisibility(android.view.View.GONE);
        pauseDownload.setOnClickListener(v -> {
            cancelDownload.set(true);
            pauseDownload.setEnabled(false);
            status.setText("Pausing download…");
        });
        column.addView(play);
        // Both download and pause controls belong on the Play tab, not Settings.
        column.addView(pauseDownload);
        // Restore the original first-Play HTTPS installer on an explicit button.
        // It downloads independently of native graphics initialization.
        downloadGameButton = new Button(this);
        downloadGameButton.setText("Download game files (HTTPS)");
        downloadGameButton.setOnClickListener(v -> downloadGameFiles());
        column.addView(downloadGameButton);
        playEngine = new Button(this);
        playEngine.setText("TEST NATIVE ARM64 (wasm2c) • experimental");
        playEngine.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Experimental native engine")
            .setMessage("This uses compiled ARM64 wasm2c game code instead of Wasmtime. It has not yet passed real-device gameplay or save tests. Back up your saves before testing. Keep Wasmtime as your stable fallback.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start native test", (dialog, which) -> launch(EngineActivity.class))
            .show());
        column.addView(playEngine);
        // Native-only APK: one Play button, no Wasmtime engine choice.
        playEngine.setVisibility(android.view.View.GONE);
        // Bug reports go to the project issue tracker; the social link reaches the
        // author directly. Both open in the browser, never inside the app.
        TextView support = new TextView(this);
        support.setText("Nightlane Android is an unofficial, AI-assisted fork. Based on Grand V and the BytArch Android port (GPL-3.0-or-later). This project is not affiliated with Rockstar Games or the original creators. Original copyright notices and source history remain intact.");
        column.addView(support);
        Button reportBug = new Button(this);
        reportBug.setText("Report a Nightlane bug on GitHub");
        reportBug.setOnClickListener(v -> openUrl("https://github.com/lilblock1/gta5mobile-android-test-improvements/issues"));
        column.addView(reportBug);
        Button contactAuthor = new Button(this);
        contactAuthor.setText("Original port author (X)");
        contactAuthor.setOnClickListener(v -> openUrl("https://x.com/MikeJohndlvl"));
        column.addView(contactAuthor);
        Button upstreamProject = new Button(this);
        upstreamProject.setText("View original BytArch port");
        upstreamProject.setOnClickListener(v -> openUrl("https://github.com/bytarch/gta5mobile-android"));
        column.addView(upstreamProject);
        ready();
        organizeLauncher(column, title, subtitle, warning, importButton, graphicsHeader, play, support);
    }

    // Turn the existing fully wired controls into a compact, navigable launcher.
    // Reparenting preserves listeners, settings and the game launch flow.
    private void organizeLauncher(LinearLayout root, android.view.View title, android.view.View subtitle,
            android.view.View warning, android.view.View libraryStart, android.view.View settingsStart,
            android.view.View playStart, android.view.View supportStart) {
        java.util.ArrayList<android.view.View> original = new java.util.ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) original.add(root.getChildAt(i));
        int library = original.indexOf(libraryStart);
        int settingsIndex = original.indexOf(settingsStart);
        int launch = original.indexOf(playStart);
        int supportIndex = original.indexOf(supportStart);
        if (library < 0 || settingsIndex < library || launch < settingsIndex || supportIndex < launch) return;

        root.removeAllViews();
        root.addView(title);
        root.addView(subtitle);

        TextView tagline = new TextView(this);
        tagline.setText("Unofficial fork of Grand V and the BytArch Android port.\nIndependent AI-assisted improvements; original creators credited.");
        tagline.setTextColor(0xFF9BACBF);
        tagline.setTextSize(14);
        root.addView(tagline);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(tabs);

        LinearLayout[] pages = new LinearLayout[4];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setPadding(dp(12), dp(16), dp(12), dp(18));
            pages[i].setBackground(rounded(0xFF111B2A, 0xFF263A50));
            root.addView(pages[i]);
        }

        for (int i = 2; i < original.size(); i++) {
            android.view.View view = original.get(i);
            int page = i < library ? 0 : i < settingsIndex ? 1 : i < launch ? 2 : i < supportIndex ? 0 : 3;
            if (view.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) view.getParent()).removeView(view);
            pages[page].addView(view);
        }

        // Play is the primary action, ahead of diagnostics and advanced options.
        if (play instanceof Button) {
            Button primary = (Button) play;
            primary.setText("PLAY WITH NIGHTLANE");
            primary.setBackground(rounded(0xFF007FBB, 0xFF40C9FF));
            primary.setMinHeight(dp(60));
        }

        String[] labels = {"Play", "Library", "Settings", "Help"};
        Button[] buttons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button tab = new Button(this);
            tab.setAllCaps(false);
            tab.setText(labels[i]);
            tab.setTextSize(12);
            tab.setSingleLine(true);
            tab.setPadding(dp(2), dp(8), dp(2), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(dp(2), dp(10), dp(2), dp(16));
            tabs.addView(tab, lp);
            buttons[i] = tab;
            tab.setOnClickListener(v -> {
                for (int j = 0; j < pages.length; j++) {
                    pages[j].setVisibility(j == index ? android.view.View.VISIBLE : android.view.View.GONE);
                    buttons[j].setTextColor(j == index ? 0xFFFFFFFF : 0xFF9BACBF);
                    buttons[j].setBackground(rounded(j == index ? 0xFF0876AD : 0xFF1C2A3D,
                        j == index ? 0xFF40C9FF : 0xFF344760));
                }
            });
        }
        buttons[0].performClick();
    }

    // The native engine core is a separate library in the APK. A build without
    // it cannot run the engine, so the launcher must not offer the button.
    private boolean engineLibraryPresent() {
        String lib = System.mapLibraryName("gta5engine");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(getApplicationInfo().sourceDir)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith("/" + lib)) return true;
            }
        } catch (Exception e) {
            // Fall through to the extracted library directory.
        }
        try {
            return new File(new File(getApplicationInfo().nativeLibraryDir), lib).isFile();
        } catch (Exception e) {
            return false;
        }
    }

    // Never turn the Play button into an inert control: report missing files or
    // native initialization problems on screen, and preserve the old first-Play
    // HTTPS download path for devices without game data.
    private void playNative() {
        if (importing) {
            status.setText("Game files are downloading or importing. Finish setup before playing.");
            return;
        }
        status.setText("Checking game files and native engine…");
        if (!nativeLoaded.get()) {
            Exception failed = nativeFailure.get();
            if (failed != null) {
                status.setText("Native renderer failed to load. See the error dialog or Export logs in Library.");
                error(failed);
            } else {
                status.setText("Native renderer is still loading. Try Play again once initialization completes.");
                ensureNative();
            }
            return;
        }
        if (!engineLibraryPresent()) {
            status.setText("Native ARM64 game engine is missing from this APK.");
            error(new IllegalStateException("libgta5engine.so missing. Install the complete native-only APK."));
            return;
        }
        try {
            String folder = folderPath();
            if (folder == null) {
                status.setText("The selected game folder cannot be read.");
                new AlertDialog.Builder(this)
                    .setTitle("Game folder unavailable")
                    .setMessage("The selected game folder is unreadable. Choose a different folder in Library, or download a copy into app storage.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download game files", (dialog, which) -> downloadGameFiles())
                    .show();
                return;
            }
            if (!nativePrepare(getFilesDir().getAbsolutePath())) {
                if (folder.isEmpty()) {
                    status.setText("Game data is not installed. Starting the original HTTPS installer…");
                    downloadGameFiles();
                } else {
                    status.setText("Selected folder has incomplete game resources.");
                    new AlertDialog.Builder(this)
                        .setTitle("Game data incomplete")
                        .setMessage("The selected folder is missing required game resources. Choose a compatible folder in Library, or download to app storage.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download game files", (dialog, which) -> downloadGameFiles())
                        .show();
                }
                return;
            }
            status.setText("Launching native ARM64 engine…");
            launch(EngineActivity.class);
        } catch (Throwable t) {
            android.util.Log.e("Nightlane", "Play button failed", t);
            status.setText("Play failed: " + t);
            error(new IllegalStateException("Play could not start: " + t, t));
        }
    }

    // Persist the chosen settings, then start the requested game activity.
    private void launch(Class<?> activity) {
        // The proxy is initialized and verified in the isolated game process.
        if (DriverPackages.selected(this).startsWith("custom:")
                && !DriverPackages.nativeProxyInstalled(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Custom Vulkan driver unavailable")
                .setMessage("This APK does not contain the native Vulkan proxy libraries. Rebuild with -PwithDriverProxy=true, or launch using the stock Samsung driver?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use system driver", (dialog, which) -> {
                    try {
                        DriverPackages.select(this, "system");
                        launch(activity);
                    } catch (IOException error) { status.setText("Cannot select system driver: " + error.getMessage()); }
                }).show();
            return;
        }
        try {
            double renderScale = Double.parseDouble(scale.getText().toString());
            if (!Double.isFinite(renderScale) || renderScale < 0.50 || renderScale > 1) throw new IllegalArgumentException("Native render scale must be between 0.50 and 1.0");
            settings.put("scale", renderScale);
            settings.put("android_msaa_mode", new String[]{"default", "adaptive", "off"}[msaaMode.getSelectedItemPosition()]);
            settings.put("start_mode", mode.getSelectedItemPosition() + 1);
            settings.put("android_heap_gb", heap.getSelectedItemPosition() + 3);
            settings.put("android_auto_heap", automaticHeap.isChecked());
            settings.put("native_cpu_workers", new int[]{2, 4, 6}[nativeCpuWorkers.getSelectedItemPosition()]);
            settings.put("nightlane_pipeline_workers",
                new int[]{1, 2, 4}[pipelineWorkers.getSelectedItemPosition()]);
            // On the native backend this controls SurfaceView's internal Vulkan
            // buffer size, not the old Wasmtime-specific offscreen resolution.
            settings.put("nightlane_native_render_scale", Math.max(0.50, renderScale));
            settings.put("nightlane_read_ahead", readAhead.isChecked());
            settings.put("nightlane_readback_reuse", readbackReuse.isChecked());
            settings.put("nightlane_prelaunch_warmup", prelaunchWarmup.isChecked());
            settings.put("nightlane_shadow_fix", shadowFix.isChecked());
            settings.put("nightlane_frame_queue", new String[]{"original", "low_latency", "minimal"}[frameQueue.getSelectedItemPosition()]);
            settings.put("streamer_percentage", STREAMING_BUDGETS[streamingBudget.getSelectedItemPosition()]);
            settings.put("nightlane_worldstream_config_version", 1);
            settings.put("nightlane_worldstream_mode",
                worldstreamMode.getSelectedItemPosition() == 1 ? "experimental" : "original");
            settings.put("texture_quality", settings.optInt("texture_quality", 0));
            settings.put("graphics", graphicsChoices());
            settings.put("extra_args", graphicsArgs());
            settings.put("gpu_cpu_bc", cpuBc.isChecked());
            settings.put("gpu_software_clip", softwareClip.isChecked());
            settings.put("nightlane_present",
                nightlanePresent.getSelectedItemPosition() == 2 ? "sharp"
                : nightlanePresent.getSelectedItemPosition() == 1 ? "smooth" : "native");
            settings.put("nightlane_lod",
                new String[]{"performance", "balanced", "detail", "original"}[nightlaneLod.getSelectedItemPosition()]);
            settings.put("nightlane_sharpness",
                new double[]{0.15, 0.30, 0.45}[nightlaneSharpness.getSelectedItemPosition()]);
            settings.put("nightlane_thermal_adaptive", nightlaneThermal.isChecked());
            settings.put("nightlane_compact_hud", nightlaneCompactHud.isChecked());
            settings.put("nightlane_touch_free_aim", touchFreeAim.isChecked());
            settings.put("nightlane_touchpad_camera", touchpadCamera.isChecked());
            settings.put("nightlane_hud_scale",
                new double[]{0.85, 1.0, 1.15}[nightlaneHudSize.getSelectedItemPosition()]);
            settings.put("nightlane_hud_opacity",
                new double[]{0.16, 0.24, 0.36}[nightlaneHudOpacity.getSelectedItemPosition()]);
            settings.put("fullscreen", true);
            settings.put("mute", mute.isChecked());
            settings.put("multiplayer", multiplayer.isChecked());
            settings.put("relay_url", relayUrl.getText().toString().trim());
            settings.put("relay_room", relayRoom.getText().toString().trim());
            // Not trimmed: an access code may legitimately begin or end with a space.
            settings.put("relay_key", relayKey.getText().toString());
            settings.put("player_name", name.getText().toString().trim());
            settings.put("last_magnet", magnetField.getText().toString().trim());
            settings.put("touch_sensitivity", LookSensitivity.fromSlider(sensitivity.getProgress()));
            // Both backend buttons use exactly the same local game data and
            // settings. Record the chosen runtime for future log comparisons;
            // this is metadata, not an instruction to auto-switch backends.
            boolean nativeBackend = activity == EngineActivity.class;
            settings.put("nightlane_execution_backend", "native_wasm2c");
            saveSettings();
            if (!nativePrepare(getFilesDir().getAbsolutePath())) {
                String selectedFolder = folderPath();
                if (selectedFolder == null || !selectedFolder.isEmpty()) {
                    status.setText("Selected game folder is incomplete or unreadable. Pick a compatible folder or use the downloaded copy.");
                    return;
                }
                installOnFirstPlay(activity);
                return;
            }
            String backend = activity == EngineActivity.class ? "native_wasm2c" : "wasmtime";
            try (FileOutputStream output = new FileOutputStream(logFile(), true)) {
                output.write(("[nightlane-engine] launching=" + backend
                    + " native_library_present=" + engineLibraryPresent()
                    + " experimental=" + (activity == EngineActivity.class) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            } catch (IOException logError) {
                android.util.Log.w("Nightlane", "Unable to record backend launch", logError);
            }
            // A failed isolated :engine process will leave a diagnostic that the
            // launcher shows after returning, even when it crashes immediately.
            new File(getFilesDir(), "nightlane-last-launch-error.txt").delete();
            if (activity == EngineActivity.class
                    && settings.optBoolean("nightlane_prelaunch_warmup", true)) {
                startPrelaunchWarmup(activity);
            } else {
                startActivity(new Intent(this, activity));
            }
        } catch (Throwable t) {
            android.util.Log.e("Nightlane", "Native activity could not launch", t);
            status.setText("Cannot launch native game: " + t);
            error(new IllegalStateException("Cannot launch native game: " + t, t));
        }
    }

    // Prelaunch preparation happens in the existing launcher worker, not the
    // game thread. Display percentages correspond to completed shader source
    // checks and completed local file reads. Skip never modifies installed data.
    private void startPrelaunchWarmup(Class<?> activity) {
        if (!warmingNative.compareAndSet(false, true)) return;
        AtomicBoolean skip = new AtomicBoolean(false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(12), dp(22), dp(16));
        TextView detail = new TextView(this);
        detail.setText("Finding shaders and cached assets…");
        detail.setTextColor(0xffe4ecf5);
        detail.setTextSize(15);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(false);
        bar.setProgress(0);
        TextView percent = new TextView(this);
        percent.setText("0%");
        percent.setTextColor(0xff70d8ff);
        percent.setTextSize(13);
        layout.addView(detail);
        layout.addView(bar);
        layout.addView(percent);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Preparing Nightlane")
            .setView(layout)
            .setNegativeButton("Skip", null)
            .create();
        dialog.setCancelable(false);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            skip.set(true);
            detail.setText("Skipping optional preparation…");
        });
        String configuredPath = settings.optString("game_folder_path", "").trim();
        File preferred = configuredPath.isEmpty() ? null : new File(configuredPath);
        final File root = preferred != null && new File(preferred, "data/manifest.json").isFile()
            ? preferred : new File(getFilesDir(), "game");
        worker.execute(() -> {
            String error = null;
            try {
                NightlanePrecache.prepare(this, root, skip,
                    (done, message) -> runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        bar.setProgress(Math.max(0, Math.min(100, done)));
                        percent.setText(Math.max(0, Math.min(100, done)) + "%");
                        detail.setText(message);
                    }),
                    source -> {
                        try { return nativeShaderValidate(source); }
                        catch (Throwable ignored) { return false; }
                    });
            } catch (Exception e) {
                error = e.toString();
            }
            final String failure = error;
            runOnUiThread(() -> {
                warmingNative.set(false);
                if (dialog.isShowing()) dialog.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (failure != null) {
                    android.util.Log.w("Nightlane", "Optional launch pre-cache was not completed: " + failure);
                }
                status.setText(skip.get() ? "Preparation skipped. Launching native ARM64 game…"
                    : failure == null ? "Shader and file preparation complete. Launching native ARM64 game…"
                    : "Using original game loading. Launching native ARM64 game…");
                startActivity(new Intent(this, activity));
            });
        });
    }

    // Opens a link with whatever browser the phone has. Devices without a
    // browser are rare but real, so the failure is reported, not fatal.
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            error(e);
        }
    }

    private File config() { return new File(getFilesDir(), "config.json"); }
    private Spinner choice(LinearLayout column, String label, String[] options, int selected) {
        TextView text = new TextView(this); text.setText(label); column.addView(text);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selected); column.addView(spinner);
        return spinner;
    }
    private EditText field(LinearLayout column, String label, String value) {
        TextView text = new TextView(this); text.setText(label); column.addView(text);
        EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(value); column.addView(edit);
        return edit;
    }
    private CheckBox check(LinearLayout column, String label, boolean value) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setChecked(value); column.addView(box); return box;
    }
    // Queues the one-time library load. Nothing native may run before it lands,
    // so ready() waits here and is called again from the worker when it finishes.
    private void ensureNative() {
        if (!loadingNative.compareAndSet(false, true)) return;
        worker.execute(() -> {
            Exception thrown = null;
            try { System.loadLibrary("muguet"); }
            catch (Throwable t) {
                android.util.Log.e("Nightlane", "Native renderer failed to load", t);
                thrown = new IllegalStateException("Native renderer failed: " + t, t);
            }
            if (thrown == null) nativeLoaded.set(true); else nativeFailure.compareAndSet(null, thrown);
            final Exception failure = thrown;
            runOnUiThread(() -> {
                if (failure != null) error(failure);
                ready();
            });
        });
    }
    private void ready() {
        if (importing) { play.setEnabled(true); playEngine.setEnabled(false); downloadGameButton.setEnabled(false); return; }
        // Not ready yet: the load is in flight, and its result calls back into here.
        if (!nativeLoaded.get()) {
            play.setEnabled(true); playEngine.setEnabled(false);
            downloadGameButton.setEnabled(true);
            // The button itself explains the failure rather than silently ignoring taps.
            if (nativeFailure.get() != null) {
                status.setText(nativeFailure.get().getMessage() + " — Tap Play for details. Downloads still work.");
                return;
            }
            status.setText("Loading native renderer… Tap Play for progress.");
            ensureNative();
            return;
        }
        try {
            String folder = folderPath();
            boolean pickedSomething = !settings.optString("game_folder_uri", "").isEmpty();
            if (folder == null) {
                folderStatus.setText(pickedSomething
                    ? "That folder cannot be read. Grant file access below, or pick another folder."
                    : "No game data yet. Import a ZIP, or pick a folder that holds data/ and b/.");
                clearFolderButton.setVisibility(pickedSomething ? android.view.View.VISIBLE : android.view.View.GONE);
                grantAccessButton.setVisibility(pickedSomething && !hasAllFilesAccess() ? android.view.View.VISIBLE : android.view.View.GONE);
                play.setEnabled(true); playEngine.setEnabled(false);
                downloadGameButton.setEnabled(true);
                downloadGameButton.setVisibility(android.view.View.VISIBLE);
                status.setText(pickedSomething ? "External game folder unreadable. Download an app-local copy or choose another folder in Library." : "Tap Download game files to install the original HTTPS chunks.");
                return;
            }
            grantAccessButton.setVisibility(android.view.View.GONE);
            if (folder.isEmpty()) {
                folderStatus.setText("Using the copy imported from a ZIP.");
                clearFolderButton.setVisibility(android.view.View.GONE);
            } else {
                folderStatus.setText("Game folder: " + folder);
                clearFolderButton.setVisibility(android.view.View.VISIBLE);
                if (!folder.equals(settings.optString("game_folder_path", ""))) {
                    settings.put("game_folder_path", folder);
                    saveSettings();
                }
            }
            boolean ready = nativePrepare(getFilesDir().getAbsolutePath());
            // Keep Play actionable even if files are missing: it starts the
            // original download or offers an explicit error instead of doing nothing.
            play.setEnabled(true);
            playEngine.setEnabled(ready);
            // Keep a working download control even with an invalid external folder.
            // Unlike Play, it does not depend on nativePrepare() or nativeLoaded.
            downloadGameButton.setVisibility(android.view.View.VISIBLE);
            downloadGameButton.setEnabled(true);
            play.setText(ready ? "Play — native ARM64 (experimental)" : "Play — download game data");
            status.setText(ready ? "Game data ready."
                : folder.isEmpty() ? "Tap Download game files (HTTPS), or import your existing files. Keep the app open during setup."
                : "Selected game folder is incomplete. In Library, clear the selected folder to download.");
        } catch (Exception e) { play.setEnabled(true); playEngine.setEnabled(false); error(e); }
    }

    // The original installer ran on the first Play: the same GameDataInstaller
    // downloads the original HTTPS manifest and hash-checked chunks. Unlike
    // nativePrepare(), this is entirely Java and does not need the GPU/engine to
    // have initialized. This is important when native rendering fails at startup.
    private void downloadGameFiles() {
        if (importing) return;
        File existing = new File(getFilesDir(), "game");
        if (existing.exists()) {
            status.setText("A game folder already exists in app storage. The original downloader will not overwrite it. "
                + "Use the existing game data or back it up before replacing an incomplete installation.");
            return;
        }
        boolean externalSelected = !settings.optString("game_folder_uri", "").isEmpty()
            || !settings.optString("game_folder_path", "").isEmpty();
        if (externalSelected) {
            new AlertDialog.Builder(this)
                .setTitle("Download to app storage?")
                .setMessage("This will switch from your selected external folder to a new copy in app storage. "
                    + "Your external files will not be deleted. The original HTTPS installer supports pause and resume.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> {
                    settings.remove("game_folder_uri");
                    settings.remove("game_folder_path");
                    saveSettings();
                    installOnFirstPlay(null);
                }).show();
            return;
        }
        installOnFirstPlay(null);
    }

    private void installOnFirstPlay(Class<?> activity) {
        if (importing) return;
        importing = downloading = true;
        cancelDownload.set(false);
        play.setEnabled(true); playEngine.setEnabled(false); downloadGameButton.setEnabled(false); importButton.setEnabled(false);
        pauseDownload.setEnabled(true); pauseDownload.setVisibility(android.view.View.VISIBLE);
        progress.setIndeterminate(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            Exception failure = null;
            try {
                GameDataInstaller installer = new GameDataInstaller(GameDataInstaller.HTTPS, cancelDownload::get,
                    message -> runOnUiThread(() -> { if (!isDestroyed()) status.setText(message); }));
                installer.install(getFilesDir(), root -> copyEngineAssets("b/" + GameDataInstaller.BUILD, root));
            } catch (Exception e) { failure = e; }
            Exception result = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                importing = downloading = false;
                importButton.setEnabled(true); pauseDownload.setVisibility(android.view.View.GONE);
                progress.setIndeterminate(false);
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                ready();
                if (result != null) error(result);
                else if (activity != null) launch(activity);
                else status.setText("Game files downloaded and verified. Tap Play to launch the native engine.");
            });
        });
    }

    private void copyEngineAssets(String path, File root) throws Exception {
        if (cancelDownload.get()) throw new InterruptedIOException("Download paused. Tap Play to resume.");
        String[] children = getAssets().list(path);
        if (children != null && children.length > 0) {
            for (String child : children) copyEngineAssets(path + "/" + child, root);
            return;
        }
        File target = new File(root, path);
        InputStream asset;
        try { asset = getAssets().open(path); }
        catch (FileNotFoundException e) {
            throw new IOException("This APK is missing an engine resource: " + path + ". Install the complete Nightlane build.", e);
        }
        try (InputStream in = asset) {
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create engine resource folder");
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[128 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelDownload.get()) throw new InterruptedIOException("Download paused. Tap Play to resume.");
                    out.write(buffer, 0, n);
                }
            }
        }
    }
    // The picker hands back a tree Uri but the engine reads plain paths, so the
    // tree document id is mapped back to /storage/emulated/0/... . Shared storage
    // keeps a real path an app can read; an SD card or USB volume has none, so it
    // is reported rather than silently loading nothing.
    // Returns "" when no folder was ever picked, and null when the picked one is unusable.
    private String folderPath() {
        String stored = settings.optString("game_folder_uri", "");
        if (!stored.isEmpty()) {
            Uri tree = Uri.parse(stored);
            try {
                getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The grant was revoked or the pick was never persistable; the check below decides.
            }
            try {
                String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
                int colon = id.indexOf(':');
                if (colon >= 0 && "primary".equals(id.substring(0, colon))) {
                    File base = new File("/storage/emulated/0/" + id.substring(colon + 1));
                    String root = gameRoot(base.getAbsolutePath());
                    if (root != null) return root;
                }
            } catch (Exception ignored) {
                // Fall through to the path we resolved earlier.
            }
        }
        // The tree Uri is gone or no longer granted - a reinstall, a revoked
        // grant, a cancelled pick. The path resolved from it is still readable
        // with all-files access, so keep using it: dropping the folder here is
        // what made a working install look like "Import game data to begin."
        String path = settings.optString("game_folder_path", "").trim();
        if (!path.isEmpty()) {
            String root = gameRoot(path);
            if (root != null) return root;
        }
        return stored.isEmpty() ? "" : null;
    }
    // The release wraps the game in a folder: GrandV-assets/mirror/playgta5.com
    // holds data/ and b/<build>/, and the instructions tell people to pick the
    // outer folder. So the game folder is looked up inside the picked one.
    private String gameRoot(String path) {
        if (nativeLoaded.get()) {
            try {
                String root = nativeGameRoot(path);
                if (root != null) return root;
            } catch (Exception ignored) { }
        }
        for (File candidate : new File[]{new File(path), new File(path, "game")}) {
            if (readable(candidate)) return candidate.getAbsolutePath();
        }
        return null;
    }
    // Opening the manifest is the test, not listing it: on /sdcard an app can stat
    // a file it is not allowed to read, and that false "yes" is what used to look
    // like a broken install instead of a missing permission.
    private static boolean readable(File dir) {
        try (InputStream in = new FileInputStream(new File(dir, "data/manifest.json"))) { return in.read() >= 0; }
        catch (Exception e) { return false; }
    }
    private static boolean hasAllFilesAccess() {
        return android.os.Build.VERSION.SDK_INT < 30 || new android.os.Environment().isExternalStorageManager();
    }
    private void saveSettings() {
        try {
            File temp = new File(getFilesDir(), "config.json.new");
            Files.write(temp.toPath(), settings.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), config().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { error(e); }
    }
    // Legacy Wasmtime saved in files/saves. The native-only engine passes
    // its resolved GAME DIRECTORY as pg_files_init(root), so it saves under
    // <game>/userdata and <game>/sandbox. The old backup ignored those files.
    private File saves() { return new File(getFilesDir(), "saves"); }
    private File nativeSaveGameRoot() {
        String picked = "";
        try {
            JSONObject cfg = new JSONObject(new String(
                Files.readAllBytes(config().toPath()), StandardCharsets.UTF_8));
            picked = cfg.optString("game_folder_path", "").trim();
        } catch (Exception ignored) {}
        return NativeSaveStorage.gameRoot(getFilesDir(), picked);
    }
    private File logFile() { return new File(getFilesDir(), "muguet.log"); }
    private File nativeLogFile() { return new File(getFilesDir(), "nightlane-native.log"); }
    private static void walkFiles(File dir, ArrayList<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (kid.isDirectory()) walkFiles(kid, out);
            else if (kid.isFile()) out.add(kid);
        }
    }
    private void updateSavesStatus() {
        if (savesStatus == null) return;
        File game = nativeSaveGameRoot();
        int nativeCount = 0, legacyCount = 0;
        long bytes = 0;
        for (NativeSaveStorage.Root root : NativeSaveStorage.roots(getFilesDir(), game)) {
            ArrayList<File> files = new ArrayList<>();
            walkFiles(root.directory, files);
            for (File f : files) bytes += f.length();
            if (root.prefix.startsWith("native/")) nativeCount += files.size();
            else legacyCount += files.size();
        }
        savesStatus.setText(nativeCount + " native file(s), " + legacyCount
            + " legacy file(s), " + (bytes >> 10) + " KB total."
            + (nativeCount + legacyCount == 0
                ? " No save files found yet; play and save in-game first." : ""));
    }
    private void backupSaves(Uri target) {
        status.setText("Packing saves…");
        worker.execute(() -> {
            Exception failure = null;
            int written = 0;
            try (OutputStream raw = getContentResolver().openOutputStream(target)) {
                if (raw == null) throw new IOException("That location refused to be written to");
                try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                    byte[] buffer = new byte[1 << 20];
                    File nativeGame = nativeSaveGameRoot();
                    for (NativeSaveStorage.Root root :
                            NativeSaveStorage.roots(getFilesDir(), nativeGame)) {
                        ArrayList<File> files = new ArrayList<>();
                        walkFiles(root.directory, files);
                        for (File file : files) {
                            // Never back up arbitrary paths through symlinks.
                            if (!file.getCanonicalPath().startsWith(
                                    root.directory.getCanonicalPath() + File.separator)) continue;
                            String relative = root.directory.toPath().relativize(file.toPath())
                                .toString().replace(File.separatorChar, '/');
                            String name = root.prefix + relative;
                            zip.putNextEntry(new ZipEntry(name));
                            try (InputStream in = new FileInputStream(file)) {
                                int n;
                                while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                            }
                            zip.closeEntry();
                            written++;
                        }
                    }
                }
            } catch (Exception e) { failure = e; }
            final Exception problem = failure;
            final int count = written;
            runOnUiThread(() -> {
                if (problem == null) status.setText(count == 0
                    ? "No save files found. The ZIP is empty; check the selected game folder."
                    : "Backed up " + count + " native/legacy save file(s) to " + lastName(target) + ".");
                else status.setText("Save backup failed: " + problem.getMessage());
                updateSavesStatus();
            });
        });
    }
    private void exportLogs(Uri target) {
        status.setText("Exporting logs…");
        worker.execute(() -> {
            Exception failure = null;
            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new IOException("That location refused to be written to");
                byte[] buffer = new byte[1 << 20];
                boolean included = false;
                // Native wasm2c has its own process and renders via Rust, whose
                // stderr is redirected to logcat. Its JNI bridge now persists
                // both C boot messages and Rust Vulkan logs for phone-only tests.
                for (File log : new File[]{logFile(), nativeLogFile()}) {
                    if (!log.isFile() || log.length() <= 0) continue;
                    included = true;
                    out.write(("\n----- " + log.getName() + " -----\n").getBytes(StandardCharsets.UTF_8));
                    try (InputStream in = new FileInputStream(log)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                }
                if (!included) throw new IOException("No game or native engine logs were found");
                // Include system-reported process exit reasons, even when the
                // game exits without producing a native stack trace.
                appendGameExitInfo(out);
            } catch (Exception e) { failure = e; }
            final Exception problem = failure;
            runOnUiThread(() -> status.setText(problem == null
                ? "Exported logs to " + lastName(target) + "."
                : "Log export failed: " + problem.getMessage()));
        });
    }
    private void appendGameExitInfo(OutputStream out) throws IOException {
        if (Build.VERSION.SDK_INT < 30) {
            out.write("\n[android-exit] Process exit history requires Android 11 or newer.\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        StringBuilder report = new StringBuilder("\n[android-exit] Recent game-process exits (system-reported):\n");
        try {
            ActivityManager manager = getSystemService(ActivityManager.class);
            int found = 0;
            if (manager != null) {
                for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 16)) {
                    String process = info.getProcessName();
                    // Include the current native :engine process as well as
                    // the archived Wasmtime :game process.
                    if (!(getPackageName() + ":game").equals(process)
                            && !(getPackageName() + ":engine").equals(process)) continue;
                    report.append("[android-exit] process=").append(process).append(' ');
                    report.append("[android-exit] timestamp_ms=").append(info.getTimestamp())
                        .append(" reason=").append(exitReason(info.getReason()))
                        .append(" status=").append(info.getStatus())
                        .append(" pss_kib=").append(info.getPss())
                        .append(" rss_kib=").append(info.getRss())
                        .append(" description=").append(String.valueOf(info.getDescription()).replace('\n', ' ')).append('\n');
                    if (++found >= 5) break;
                }
            }
            if (found == 0) report.append("[android-exit] No recorded game-process exit is available.\n");
        } catch (RuntimeException e) {
            report.append("[android-exit] Exit history unavailable: ").append(e).append('\n');
        }
        out.write(report.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String exitReason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            default: return Integer.toString(reason);
        }
    }
    // An archive entry must land inside the save root: a crafted name like
    // ../../config.json would otherwise overwrite the launcher's own settings.
    private static File inside(File root, String name) throws IOException {
        File out = new File(root, name);
        if (!out.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IOException("That archive contains an unsafe path: " + name);
        }
        return out;
    }
    private void restoreSaves(Uri source) {
        status.setText("Restoring saves…");
        worker.execute(() -> {
            Exception failure = null;
            int count = 0;
            try (InputStream raw = getContentResolver().openInputStream(source)) {
                if (raw == null) throw new IOException("That file could not be opened");
                try (ZipInputStream zip = new ZipInputStream(raw)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        File out = NativeSaveStorage.restoreTarget(
                            getFilesDir(), nativeSaveGameRoot(), entry.getName());
                        if (entry.isDirectory()) { out.mkdirs(); continue; }
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream os = new FileOutputStream(out)) {
                            byte[] buffer = new byte[1 << 20];
                            int n;
                            while ((n = zip.read(buffer)) != -1) os.write(buffer, 0, n);
                        }
                        count++;
                    }
                }
            } catch (Exception e) { failure = e; }
            final Exception problem = failure;
            final int restored = count;
            runOnUiThread(() -> {
                // A load error is reset by restoring, so only settings.xml needs
                // re-pinning: the engine rewrites it again on the next save anyway.
                status.setText(problem == null
                    ? "Restored " + restored + " save file(s). They take effect next launch."
                    : "Save restore failed: " + problem.getMessage());
                updateSavesStatus();
                ready();
            });
        });
    }
    private static String lastName(Uri uri) {
        String s = uri.getLastPathSegment();
        return s == null ? "the chosen file" : s;
    }
    // Each spinner holds one knob's value; the engine only ever sees the flags
    // assembled here, so nothing depends on the game persisting its own settings.
    // A preset row that does not cover every knob must not take the launcher down
    // with it, so a missing entry falls back to the engine default instead.
    private static String presetValue(int preset, int row) {
        String[] values = PRESETS[preset];
        return row < values.length ? values[row] : "auto";
    }
    private void applyPreset(int preset) {
        for (int i = 0; i < GRAPHICS.length; i++) select(graphicsSpinners[i], GRAPHICS[i], presetValue(preset, i));
    }
    private void restoreGraphics() {
        JSONObject saved = settings.optJSONObject("graphics");
        for (int i = 0; i < GRAPHICS.length; i++) {
            String flag = GRAPHICS[i][1];
            select(graphicsSpinners[i], GRAPHICS[i], saved == null ? presetValue(0, i) : saved.optString(flag, presetValue(0, i)));
        }
    }
    private static void select(Spinner spinner, String[] row, String value) {
        for (int i = 2; i < row.length; i++) {
            if (row[i].equals(value)) { spinner.setSelection(i - 2); return; }
        }
        spinner.setSelection(0);
    }
    // The spinners show friendly names, so the engine's value is read back by
    // position: DISPLAY index N is GRAPHICS[i][2 + N].
    private String graphicsValue(int row) {
        return GRAPHICS[row][2 + graphicsSpinners[row].getSelectedItemPosition()];
    }
    private JSONObject graphicsChoices() {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i < GRAPHICS.length; i++) {
                String value = graphicsValue(i);
                if (!"auto".equals(value)) out.put(GRAPHICS[i][1], value);
            }
        } catch (Exception e) { error(e); }
        return out;
    }
    private JSONArray graphicsArgs() {
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < GRAPHICS.length; i++) {
                String value = graphicsValue(i);
                if ("auto".equals(value)) continue;
                out.put("off".equals(value) ? GRAPHICS[i][1] : GRAPHICS[i][1] + "=" + value);
            }
        } catch (Exception e) { error(e); }
        return out;
    }
    private void error(Exception e) {
        if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle("Nightlane Android").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
    }
    // Called by Rust on the import worker, never directly updates UI there.
    public void importProgress(long done, long total) {
        runOnUiThread(() -> {
            progress.setIndeterminate(false);
            progress.setProgress(total == 0 ? 0 : (int) (1000.0 * done / total));
            status.setText("Extracting: " + (done >> 20) + " / " + (total >> 20) + " MB");
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_DRIVER_ZIP) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                status.setText("Validating driver package…");
                worker.execute(() -> {
                    try {
                        String name = DriverPackages.importZip(this, data.getData());
                        runOnUiThread(() -> status.setText("Imported " + name + ". Select it in Vulkan driver settings. Native driver activation is not enabled yet."));
                    } catch (Exception e) {
                        runOnUiThread(() -> status.setText("Driver import failed: " + e.getMessage()));
                    }
                });
            }
            return;
        }

        if (request == PICK_SAVE_ZIP) {
            if (result == RESULT_OK && data != null && data.getData() != null) backupSaves(data.getData());
            else if (result != RESULT_CANCELED) status.setText("No location chosen for the backup.");
            return;
        }
        if (request == PICK_SAVE_ZIP + 1) {
            if (result == RESULT_OK && data != null && data.getData() != null) restoreSaves(data.getData());
            else if (result != RESULT_CANCELED) status.setText("No archive chosen.");
            return;
        }
        if (request == PICK_SAVE_LOG) {
            if (result == RESULT_OK && data != null && data.getData() != null) exportLogs(data.getData());
            else if (result != RESULT_CANCELED) status.setText("No location chosen for the logs.");
            return;
        }
        if (request == PICK_MAGNET) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) return;
                // Pull the magnet out of whatever the file holds (plain text, a
                // .torrent with a magnet comment, a wrapper page) and keep only it.
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                int at = text.indexOf("magnet:?");
                if (at < 0) { status.setText("No magnet link found in that file."); return; }
                int end = at;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
                magnetField.setText(text.substring(at, end));
                status.setText("Magnet loaded. Tap Open torrent client.");
            } catch (Exception e) { status.setText("Could not read that file."); }
            return;
        }
        if (request == PICK_FOLDER) {
            if (result != RESULT_OK || data == null || data.getData() == null) {
                if (result != RESULT_CANCELED) status.setText("Folder pick cancelled.");
                return;
            }
            Uri tree = data.getData();
            int keep = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                // Without a persisted grant the folder is readable for this one
                // launch only, so the picker would have to be used every time.
                getContentResolver().takePersistableUriPermission(tree, keep);
                String path = folderPath();
                if (path == null) {
                    status.setText("No game data in that folder. Pick the folder that holds the game - the app looks inside it, so GrandV-assets works.");
                    return;
                }
                settings.put("game_folder_uri", tree.toString());
                // The game runs in its own process, which cannot see anything the
                // launcher passes it, so the resolved path is what it reads.
                settings.put("game_folder_path", path);
                saveSettings();
                ready();
            } catch (Exception e) { status.setText("Android would not grant lasting access to that folder."); }
            return;
        }
        if (request != PICK_ZIP) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            // Cancelled or nothing usable picked. Say so instead of returning silently.
            if (result != RESULT_CANCELED) status.setText("Import cancelled.");
            return;
        }
        // Every one of these used to return without a word, so tapping Import
        // appeared to do nothing at all. Tell the user which one blocked them.
        if (importing) { status.setText("An import is already running."); return; }
        Uri uri = data.getData();
        importing = true;
        play.setEnabled(false); playEngine.setEnabled(false); importButton.setEnabled(false); progress.setIndeterminate(true);
        status.setText("Copying ZIP to private cache. Keep the app open; allow space for the ZIP and extracted data.");
        worker.execute(() -> {
            File archive = new File(getCacheDir(), "game-import.zip");
            Exception failure = null;
            long lastReported = 0;
            try {
                // Copying a multi-GB ZIP out of the content provider takes minutes,
                // so report it. The provider often cannot give a size, in which case
                // the copy stays indeterminate rather than showing a bogus fraction.
                long total = 0;
                try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int column = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                        if (column >= 0 && !c.isNull(column)) total = c.getLong(column);
                    }
                }
                final long known = total;
                runOnUiThread(() -> {
                    if (known > 0) progress.setIndeterminate(false);
                    status.setText(known > 0
                        ? "Copying ZIP: 0 / " + (known >> 20) + " MB"
                        : "Copying ZIP to private cache. Keep the app open; allow space for the ZIP and extracted data.");
                });
                long copied = 0;
                try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(archive)) {
                    if (input == null) throw new IOException("Cannot open the selected ZIP");
                    byte[] buffer = new byte[1 << 20];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        output.write(buffer, 0, n);
                        copied += n;
                        if (known > 0 && copied - lastReported >= 32L << 20) {
                            lastReported = copied;
                            final long at = copied;
                            runOnUiThread(() -> {
                                progress.setIndeterminate(false);
                                progress.setProgress((int) (1000.0 * at / known));
                                status.setText("Copying ZIP: " + (at >> 20) + " / " + (known >> 20) + " MB");
                            });
                        }
                    }
                }
                runOnUiThread(() -> {
                    status.setText("Extracting (this can take several minutes)…");
                    progress.setIndeterminate(true);
                });
                nativeImport(archive.getAbsolutePath());
            } catch (Exception e) { failure = e; }
            finally { archive.delete(); }
            Exception error = failure;
            runOnUiThread(() -> {
                importing = false; importButton.setEnabled(true); progress.setIndeterminate(false);
                ready();
                if (error != null) error(error);
            });
        });
    }
    // Errors from :engine are written before returning to the launcher because
    // Java exceptions in another Android process cannot be caught here directly.
    private void showLastLaunchError() {
        File report = new File(getFilesDir(), "nightlane-last-launch-error.txt");
        if (!report.isFile()) return;
        try {
            String details = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
            report.delete();
            if (details.length() > 1200) details = details.substring(0, 1200);
            status.setText("Native startup failed: " + details);
            new AlertDialog.Builder(this)
                .setTitle("Native engine startup failed")
                .setMessage(details + "\n\nUse Export logs in Library to share diagnostics.")
                .setPositiveButton("OK", null)
                .show();
        } catch (Exception e) {
            android.util.Log.e("Nightlane", "Could not read engine startup diagnostics", e);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (settings != null) {
            if (firstResume) firstResume = false;
            else ready();
            showLastLaunchError();
        }
    }
    @Override protected void onDestroy() {
        if (downloading) cancelDownload.set(true);
        worker.shutdown(); super.onDestroy();
    }
}
