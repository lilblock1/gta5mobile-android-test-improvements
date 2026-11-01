// JNI bridge: lets the Java Android shell drive the vendored wasm2c engine core.
//
// This is the only file that knows about both JNI and EngineHost.h. Everything
// above it (GameActivity, TouchControls, LauncherActivity) talks to plain Java;
// everything below it is the native engine. The graphics backend is reached only
// through PGAttachVulkanWindow / the PGStart* call the core makes on its own
// thread, so this bridge does not care whether the renderer is the headless stub
// or the real Vulkan one yet.
//
// Input model: Muguet's touch layer produces a keyboard/mouse style state (a set
// of key names + a few bits + a look delta). The engine wants an analog gamepad
// (PGPadState) so GTA's own pad code runs assisted aim and analog driving. We
// translate between the two here, once, on the JNI input path.
//
// Threading: the engine boots on its own thread inside PGStartEngine and stays
// there. JNI calls that touch engine state (pad, audio) are safe to call from the
// Java UI/game thread; the engine reads the atomics we publish.

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <unistd.h>

#include "EngineHost.h"
#include "NativeRenderer.h"
#include "PGGraphicsAndroid.h"

#define LOG_TAG "Gta5Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static jobject  g_activity = NULL;        // dev/muguet/EngineActivity instance (global ref)
static jmethodID g_status_method = NULL;  // void onEngineStatus(String)
static jmethodID g_set_status_method = NULL; // void setStatus(String)

// Persist C engine and Rust renderer startup messages into a bounded,
// app-private diagnostics file. The launcher can export this together with
// the normal Wasmtime log; logcat access is not required on the phone.
#define PG_NATIVE_LOG_LIMIT (8 * 1024 * 1024)
static pthread_mutex_t nativeLogLock = PTHREAD_MUTEX_INITIALIZER;
static char nativeLogPath[1024];

static void native_log_to_file(const char *component, const char *line) {
  if (!line) return;
  pthread_mutex_lock(&nativeLogLock);
  if (nativeLogPath[0]) {
    FILE *file = fopen(nativeLogPath, "a");
    if (file) {
      if (fseek(file, 0, SEEK_END) == 0) {
        long length = ftell(file);
        if (length >= 0 && length < PG_NATIVE_LOG_LIMIT)
          fprintf(file, "[%s] %.1500s\n", component, line);
      }
      fclose(file);
    }
  }
  pthread_mutex_unlock(&nativeLogLock);
}

static void nativeSetLogPath(JNIEnv *env, jobject thiz, jstring path) {
  (void)thiz;
  if (!path) return;
  const char *value = (*env)->GetStringUTFChars(env, path, NULL);
  if (!value) return;
  pthread_mutex_lock(&nativeLogLock);
  if (strlen(value) < sizeof(nativeLogPath)) {
    snprintf(nativeLogPath, sizeof(nativeLogPath), "%s", value);
    // This file describes a single native launch so old failures cannot be
    // mistaken for the current trial. The separate Wasmtime log is untouched.
    FILE *file = fopen(nativeLogPath, "w");
    if (file) {
      fputs("[native] New native ARM64 engine session\n", file);
      fclose(file);
    }
  }
  pthread_mutex_unlock(&nativeLogLock);
  (*env)->ReleaseStringUTFChars(env, path, value);
}


// Android sends an app process' stderr to /dev/null, so the renderer's own
// prints (the Rust library logs through eprintln) would be invisible. Pipe
// stderr into logcat instead: one line per line, tagged like the engine logs.
// The pipe's read end is what the pump reads; fd 2 is its write end.
static void *stderr_pump(void *unused) {
  int fd = (int)(intptr_t)unused;
  char line[1024];
  size_t used = 0;
  for (;;) {
    char c;
    ssize_t n = read(fd, &c, 1);
    if (n <= 0) {
      if (n < 0 && errno == EINTR) continue;
      break;
    }
    if (c == '\n') {
      line[used] = 0;
      // The engine's own messages already reach logcat through its log
      // callback; only the renderer's lines are new here.
      if (strncmp(line, "[native-port] ", 13)) {
        LOGI("%s", line);
        native_log_to_file("renderer", line);
      }
      used = 0;
    } else if (used + 1 < sizeof line) {
      line[used++] = c;
    } else {
      line[used] = 0;
      if (strncmp(line, "[native-port] ", 13)) {
        LOGI("%s", line);
        native_log_to_file("renderer", line);
      }
      used = 0;
    }
  }
  return NULL;
}

static void pump_stderr_to_logcat(void) {
  int fds[2];
  if (pipe(fds) != 0) return;
  if (dup2(fds[1], STDERR_FILENO) < 0) { close(fds[0]); close(fds[1]); return; }
  close(fds[1]);
  pthread_t thread;
  if (pthread_create(&thread, NULL, stderr_pump, (void *)(intptr_t)fds[0]) == 0) pthread_detach(thread);
  else close(fds[0]);
}

// The renderer (libmuguet.so) is loaded by Java with System.loadLibrary, which
// puts it in the app namespace but not in the global group. The engine core
// finds it with dlsym(RTLD_DEFAULT), which only searches the global group, so
// the lookup would fail and the engine would fall back to the headless stub.
// NativeRenderer.c opens the same library again, which returns it as it was
// loaded, and searches that handle directly.

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  g_vm = vm;
  pump_stderr_to_logcat();
  return JNI_VERSION_1_6;
}

// The engine calls this (via the PGLogCallback we hand to PGStartEngine) from the
// engine thread. Hop to Java to surface status text in the UI.
static void engine_log_bridge(const char *message, void *context) {
  if (!message) return;
  LOGI("%s", message);
  native_log_to_file("native", message);
  // Futex messages are tracing, not startup failures. Keep them in logs
  // but don't cover the game with a misleading "futex wait" status.
  if (strncmp(message, "Futex wait at ", 14) == 0) return;
  if (!g_vm || !g_activity || !g_set_status_method) return;
  JNIEnv *env = NULL;
  bool attached = false;
  if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
    attached = true;
  }
  jstring text = (*env)->NewStringUTF(env, message);
  if (text) {
    // setStatus marshals to the UI thread itself, so it is safe from here.
    (*env)->CallVoidMethod(env, g_activity, g_set_status_method, text);
    (*env)->DeleteLocalRef(env, text);
  }
  if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

// Remember the activity instance + its status method so engine logs reach the UI.
//
// The method is static, so JNI calls it as (env, class, activity): the activity
// is the third argument, the second one is the class object. Reading the
// instance from the second slot would hand us java.lang.Class and every
// lookup on it would fail.
static jboolean nativeCacheActivity(JNIEnv *env, jclass clazz, jobject activity) {
  (void)clazz;
  g_activity = (*env)->NewGlobalRef(env, activity);
  g_set_status_method = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, activity), "setStatus", "(Ljava/lang/String;)V");
  if (!g_set_status_method) {
    // A failed lookup leaves a pending exception; returning with it set would
    // crash the activity on the next Java call.
    (*env)->ExceptionClear(env);
    LOGE("EngineActivity.setStatus(String) not found; status text will only reach logcat.");
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

// Start the engine. gameDir is the installed game folder (contains data/ and b/).
// Runs on its own thread inside the core, so this returns once boot is kicked off;
// progress arrives through onEngineStatus.
// Persistent only within this isolated :engine process; configured once before
// boot, never on a running game's threads.
static char qualityValues[32][96];
static const char *qualityPointers[32];
static unsigned qualityCount;
static const char *const approvedQuality[] = {
  "-shaderQuality", "-textureQuality", "-pedVariety", "-vehicleVariety",
  "-shadowQuality", "-reflectionQuality", "-particleQuality", "-grassQuality",
  "-cityDensity", "-lodScale", "-pedLodBias", "-vehicleLodBias",
  "-waterQuality", "-anisotropicQualityLevel", "-postFX", "-MotionBlurStrength",
  "-noInGameDOF", "-shadowSoftness", "-SSAO", "-farclip", "-fogVolumes",
  "-particleShadows", "-shadowLongShadows", "-HDStreamingInFlight", "-nolodlights"
};
static const char *const approvedRendererEnv[] = {
  "NIGHTLANE_FRAME_QUEUE", "NIGHTLANE_SHADOW_FIX", "NIGHTLANE_PRESENT",
  "NIGHTLANE_SHARPNESS", "NIGHTLANE_THERMAL_ADAPTIVE",
  "NIGHTLANE_PIPELINE_WORKERS", "MUGUET_GPU_SOFTWARE_CLIP", "MUGUET_GPU_CPU_BC", "MUGUET_RENDER_FPS_LIMIT",
  "PG_HTTPFS_READAHEAD_KB", "NIGHTLANE_READBACK_REUSE",
  "NIGHTLANE_PIPELINE_CACHE_DIR"
};
static void nativeConfigure(JNIEnv *env, jclass clazz, jobjectArray options,
                            jint workers, jfloat streamerBudget, jobjectArray rendererVars) {
  (void)clazz;
  qualityCount = 0;
  if (options) {
    jsize count = (*env)->GetArrayLength(env, options);
    for (jsize i = 0; i < count && qualityCount < 32; ++i) {
      jstring item = (jstring)(*env)->GetObjectArrayElement(env, options, i);
      if (!item) continue;
      const char *value = (*env)->GetStringUTFChars(env, item, NULL);
      if (value && strlen(value) < sizeof qualityValues[0]) {
        const char *equal = strchr(value, '=');
        if (equal && equal > value && equal[1]) {
          size_t keyLen = (size_t)(equal - value);
          bool approved = false;
          for (unsigned j = 0; j < sizeof approvedQuality / sizeof approvedQuality[0]; ++j)
            if (strlen(approvedQuality[j]) == keyLen &&
                !strncmp(value, approvedQuality[j], keyLen)) approved = true;
          if (approved) {
            snprintf(qualityValues[qualityCount], sizeof qualityValues[0], "%s", value);
            qualityPointers[qualityCount] = qualityValues[qualityCount];
            ++qualityCount;
          }
        }
      }
      if (value) (*env)->ReleaseStringUTFChars(env, item, value);
      (*env)->DeleteLocalRef(env, item);
    }
  }
  char digits[16];
  snprintf(digits, sizeof digits, "%d", workers >= 1 && workers <= 8 ? workers : 2);
  setenv("PG_ENGINE_CORES", digits, 1);
  if (isfinite(streamerBudget) && streamerBudget >= 0.1f && streamerBudget <= 0.95f) {
    snprintf(digits, sizeof digits, "%.3f", streamerBudget);
    setenv("PG_STREAMER_PERCENTAGE", digits, 1);
  }
  if (rendererVars) {
    jsize count = (*env)->GetArrayLength(env, rendererVars);
    for (jsize i = 0; i < count && i < 32; ++i) {
      jstring item = (jstring)(*env)->GetObjectArrayElement(env, rendererVars, i);
      if (!item) continue;
      const char *value = (*env)->GetStringUTFChars(env, item, NULL);
      if (value && strlen(value) < 128) {
        const char *equal = strchr(value, '=');
        if (equal && equal[1] && !strpbrk(equal + 1, "\r\n")) {
          size_t keyLen = (size_t)(equal - value);
          for (unsigned j = 0; j < sizeof approvedRendererEnv / sizeof approvedRendererEnv[0]; ++j) {
            if (strlen(approvedRendererEnv[j]) == keyLen &&
                !strncmp(value, approvedRendererEnv[j], keyLen)) {
              char key[48];
              memcpy(key, value, keyLen);
              key[keyLen] = 0;
              setenv(key, equal + 1, 1);
              break;
            }
          }
        }
      }
      if (value) (*env)->ReleaseStringUTFChars(env, item, value);
      (*env)->DeleteLocalRef(env, item);
    }
  }
  LOGI("Native options: %u graphics flags, %u workers, streamer=%.3f",
       qualityCount, workers >= 1 && workers <= 8 ? (unsigned)workers : 2, streamerBudget);
}

static void nativeStart(JNIEnv *env, jobject thiz, jstring gameDir, jstring manifestPath,
                        jint startMode, jint width, jint height, jint frameLimit) {
  (void)thiz;
  const char *dir = (*env)->GetStringUTFChars(env, gameDir, NULL);
  const char *manifest = (*env)->GetStringUTFChars(env, manifestPath, NULL);

  // Heap ceiling: EngineHost reads PG_ENGINE_HEAP_MB. Use the value the launcher
  // already stored in config (android_heap_gb), passed here by the Java side via
  // getenv being set in nativeStartHeap below. Kept separate so the env var is set
  // before PGStartEngine's boot thread reads it.
  PGSetStartMode(startMode);
  PGSetBackBufferSize(width, height);
  // Map requested FPS to the game's actual internal VSYNC mode (0,1,2).
  // Passing literal 24/30/42/60 was ignored by PGConfigureEngine.
  const int nativeFrameMode = frameLimit <= 0 ? 0 : frameLimit <= 30 ? 2 : 1;
  PGConfigureEngine(qualityPointers, qualityCount, width, height, nativeFrameMode);
  LOGI("Native frame mapping: requested=%d fps, internal mode=%d", frameLimit, nativeFrameMode);

  LOGI("Starting engine in %s (manifest %s)", dir, manifest);

  // The renderer reads the game's WGSL shaders from the engine build folder
  // (b/<build>/shaders, the same build the engine core is pinned to). The
  // wasmtime path gets this from the launcher; the engine path points the
  // renderer at the same folder here.
  {
    char shaders[1024];
    snprintf(shaders,sizeof shaders,"%s/b/%s/shaders",dir,"8b0b5899ed");
    setenv("MUGUET_SHADERS",shaders,1);
    LOGI("Shaders: %s", shaders);
  }

  PGStartEngine(manifest, dir, engine_log_bridge, NULL);

  (*env)->ReleaseStringUTFChars(env, gameDir, dir);
  (*env)->ReleaseStringUTFChars(env, manifestPath, manifest);
}

// Attach/detach the surface the renderer presents to. surface is an ANativeWindow
// from SurfaceHolder.getSurface() (or null to detach).
//
// The renderer itself is the Rust library (libmuguet.so), the same one the
// wasmtime path uses, so both engines draw through the same wgpu code. It is
// resolved at runtime: the engine core never links against it.
typedef void (*pg_attach_window_fn)(void*,uint32_t,uint32_t);

static void nativeAttachSurface(JNIEnv *env, jobject thiz, jobject surface) {
  (void)thiz;
  ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
  PGAttachVulkanWindow(window);
  pg_attach_window_fn attach=(pg_attach_window_fn)pg_renderer_symbol("muguet_gpu_attach_window");
  if(attach){
    if(window){
      // The renderer borrows the window, so it needs a reference of its own.
      // It is not released again: the window outlives the renderer.
      ANativeWindow_acquire(window);
      int w=ANativeWindow_getWidth(window),h=ANativeWindow_getHeight(window);
      attach(window,(uint32_t)(w>0?w:1),(uint32_t)(h>0?h:1));
    } else {
      attach(NULL,0,0);
    }
  }
  if (window) ANativeWindow_release(window);  // the backend keeps its own acquire()
}

// --- input --------------------------------------------------------------------
// Muguet touch/keyboard -> engine analog pad. Keys arrive as a String[] of key
// names ("KeyW", "Space", ...) plus a touch button bitmask and a look delta,
// exactly as TouchControls produces today. We map movement/aim/actions onto the
// engine's pad so GTA runs its real controller code (assisted aim, analog drive).

#define PAD_L2 1u
#define PAD_R2 2u
#define PAD_L1 4u
#define PAD_R1 8u
#define PAD_Y 16u
#define PAD_B 32u
#define PAD_A 64u
#define PAD_X 128u
#define PAD_SELECT 256u
#define PAD_L3 512u
#define PAD_R3 1024u
#define PAD_START 2048u
#define PAD_DPAD_UP 4096u
#define PAD_DPAD_RIGHT 8192u
#define PAD_DPAD_DOWN 16384u
#define PAD_DPAD_LEFT 32768u

static float curve_axis(float v) {
  float a = fabsf(v);
  if (a < 0.12f) return 0.0f;
  float scaled = (a - 0.12f) / 0.88f;
  return copysignf(powf(scaled, 1.6f), v);
}

// keys: String[] of key names. touchButtons: muguet touch bits (1 fire, 2 aim).
// horn: boolean. lookDX/lookDY: accumulated right-stick look delta in pixels
// this frame (from TouchControls nativeLook), scaled into stick units.
static void nativeUpdatePad(JNIEnv *env, jobject thiz, jobjectArray keys,
                            jint touchButtons, jboolean horn, jfloat lookDX, jfloat lookDY,
                            jboolean useAnalogSticks) {
  (void)thiz; (void)useAnalogSticks; // always analog; the flag is reserved for tuning
  PGPadState pad;
  memset(&pad, 0, sizeof pad);

  // Right-stick input uses the Java touch displacement already scaled by
  // sensitivity. The old /320 mapping made ordinary short drags disappear
  // into the game's stick deadzone, especially while aiming down sights.
  // 96 scaled pixels = full stick deflection; clamp before publishing.
  if (isfinite(lookDX) && isfinite(lookDY)) {
    pad.rightX = fmaxf(-1.0f, fminf(1.0f, lookDX / 96.0f));
    pad.rightY = fmaxf(-1.0f, fminf(1.0f, -lookDY / 96.0f));
  }

  // Walk the pressed key set once and set the corresponding pad bits / axes.
  jsize count = keys ? (*env)->GetArrayLength(env, keys) : 0;
  for (jsize i = 0; i < count; i++) {
    jstring k = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
    if (!k) continue;
    const char *key = (*env)->GetStringUTFChars(env, k, NULL);
    if (!key) continue;

    // Movement is exclusively on the left stick: never emit D-pad events,
    // which otherwise open the phone or navigate menus while walking.
    if (!strcmp(key, "KeyA"))        { pad.leftX = -1.0f; }
    else if (!strcmp(key, "KeyD"))   { pad.leftX =  1.0f; }
    else if (!strcmp(key, "KeyW") || !strcmp(key, "Numpad8")) { pad.leftY =  1.0f; }
    else if (!strcmp(key, "KeyS") || !strcmp(key, "Numpad2")) { pad.leftY = -1.0f; }

    // GTA-style Xbox pad: A sprint, X jump, Y enter/exit, B reload.
    else if (!strcmp(key, "Space"))      { pad.buttons |= PAD_X; }
    else if (!strcmp(key, "handbrake"))  { pad.buttons |= PAD_R1; }
    else if (!strcmp(key, "KeyF"))       { pad.buttons |= PAD_Y; }
    else if (!strcmp(key, "ShiftLeft"))  { pad.buttons |= PAD_A; }
    else if (!strcmp(key, "ControlLeft")){ pad.buttons |= PAD_L3; }
    else if (!strcmp(key, "Tab"))        { pad.buttons |= PAD_L1; }
    else if (!strcmp(key, "KeyQ"))       { pad.buttons |= PAD_R1; }
    else if (!strcmp(key, "radio"))      { pad.buttons |= PAD_DPAD_LEFT; }
    else if (!strcmp(key, "lookBack") || !strcmp(key, "KeyC")) { pad.buttons |= PAD_R3; }
    else if (!strcmp(key, "KeyV"))       { pad.buttons |= PAD_SELECT; }
    else if (!strcmp(key, "KeyR"))       { pad.buttons |= PAD_B; }
    else if (!strcmp(key, "Escape"))     { pad.buttons |= PAD_START; }
    else if (!strcmp(key, "Enter"))      { pad.buttons |= PAD_A; }
    else if (!strcmp(key, "ArrowUp"))    { pad.buttons |= PAD_DPAD_UP; }
    else if (!strcmp(key, "ArrowDown"))  { pad.buttons |= PAD_DPAD_DOWN; }
    else if (!strcmp(key, "ArrowLeft"))  { pad.buttons |= PAD_DPAD_LEFT; }
    else if (!strcmp(key, "ArrowRight")) { pad.buttons |= PAD_DPAD_RIGHT; }
    else if (!strcmp(key, "Backspace"))  { pad.buttons |= PAD_B; }
    else if (!strcmp(key, "AltLeft"))    { pad.buttons |= PAD_DPAD_DOWN; }

    (*env)->ReleaseStringUTFChars(env, k, key);
  }

  // Touch-only bits.
  if (touchButtons & 1) pad.rightTrigger = 1.0f;                 // fire
  if (touchButtons & 2) pad.leftTrigger = 1.0f;                  // aim
  if (touchButtons & 4) pad.leftTrigger = 1.0f;                 // brake
  if (horn)              pad.buttons |= PAD_L3;                   // horn (vehicle)

  PGUpdateVirtualPad(pad);
}

// Analog sticks straight from a real gamepad (bypasses key mapping).
static void nativeUpdatePadAnalog(JNIEnv *env, jobject thiz,
                                  jfloat lx, jfloat ly, jfloat rx, jfloat ry,
                                  jfloat lt, jfloat rt, jint buttons) {
  (void)env; (void)thiz;
  PGPadState pad;
  memset(&pad, 0, sizeof pad);
  pad.leftX = curve_axis(lx); pad.leftY = curve_axis(ly);
  pad.rightX = curve_axis(rx); pad.rightY = curve_axis(ry);
  pad.leftTrigger = lt > 0 ? lt : 0.0f;
  pad.rightTrigger = rt > 0 ? rt : 0.0f;
  pad.buttons = (uint32_t)buttons & 0xffffu;
  PGUpdateVirtualPad(pad);
}

// A targeting-mode request is delivered to the game's own input thread,
// where the player and targeting preferences can be updated safely.
static void nativeSetTargetingMode(JNIEnv *env, jobject thiz, jint mode) {
  (void)env; (void)thiz;
  if (mode >= 0 && mode <= 3) PGSetTargetingMode(mode);
}

// --- audio --------------------------------------------------------------------
// Deliver exactly the requested stereo frame count to AudioTrack, padding
// missing mixer frames with silence. Keep the gain below full scale to avoid
// clipping when game samples are louder than the Android output expects.
#define AUDIO_CHUNK 2048
#define AUDIO_RAMP_FRAMES 64u
static float g_audioL[AUDIO_CHUNK], g_audioR[AUDIO_CHUNK];
static bool g_audioLastBlockWasGap = true;
static uint64_t g_audioLastDiagnosticNs;
static uint64_t g_audioSubmittedFrames;

static float pg_audio_soft_limit(float sample) {
  if (!isfinite(sample)) return 0.0f;
  // 15% headroom. Soft-knee limiting preserves transients instead of hard
  // saturating the PCM output on loud dialogue/explosions.
  const float gain = sample * 0.85f;
  const float magnitude = fabsf(gain);
  if (magnitude <= 0.80f) return gain;
  float above = magnitude - 0.80f;
  float limited = 0.80f + 0.19f * above / (0.19f + above);
  return copysignf(limited, gain);
}

static jint nativeAudio(JNIEnv *env, jobject thiz, jshortArray out, jint maxFrames) {
  (void)thiz;
  if (!out || maxFrames <= 0) return 0;
  // Java short[] contains 2 samples for each *stereo frame*.
  jsize sampleCapacity = (*env)->GetArrayLength(env, out);
  jint frames = maxFrames < sampleCapacity / 2 ? maxFrames : sampleCapacity / 2;
  if (frames > AUDIO_CHUNK) frames = AUDIO_CHUNK;
  if (frames <= 0) return 0;

  uint32_t real = PGFillAudio(g_audioL, g_audioR, (uint32_t)frames);
  const bool gap = real < (uint32_t)frames;
  jshort *dst = (jshort *)(*env)->GetPrimitiveArrayCritical(env, out, NULL);
  if (!dst) return 0;
  for (jint i = 0; i < frames; i++) {
    float ramp = 1.0f;
    if (i >= (jint)real) ramp = 0.0f;
    else {
      // The last valid 64 frames fade out before a mixer underrun, and
      // the first 64 fade back in after a previous underrun. This removes
      // clicks without interpolating or inventing audio samples.
      if (g_audioLastBlockWasGap && i < AUDIO_RAMP_FRAMES)
        ramp *= (float)(i + 1) / (float)AUDIO_RAMP_FRAMES;
      if (gap && (uint32_t)(real - i) <= AUDIO_RAMP_FRAMES)
        ramp *= (float)(real - i) / (float)AUDIO_RAMP_FRAMES;
    }
    float l = pg_audio_soft_limit(g_audioL[i] * ramp);
    float r = pg_audio_soft_limit(g_audioR[i] * ramp);
    dst[i * 2]     = (jshort)(l * 32767.0f);
    dst[i * 2 + 1] = (jshort)(r * 32767.0f);
  }
  (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
  g_audioLastBlockWasGap = gap;
  g_audioSubmittedFrames += (uint64_t)frames;

  // Log accumulated mixer underflows and overruns every 10 seconds. Audio
  // glitches from a starved guest renderer then become diagnosable instead
  // of being confused with sample-amplitude clipping.
  struct timespec t = {0};
  if (clock_gettime(CLOCK_MONOTONIC, &t) == 0) {
    uint64_t ns = (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
    if (!g_audioLastDiagnosticNs) g_audioLastDiagnosticNs = ns;
    if (ns - g_audioLastDiagnosticNs >= 10000000000ull) {
      uint64_t underrun = 0, overrun = 0;
      PGGetAudioDiagnostics(&underrun, &overrun);
      char message[200];
      snprintf(message, sizeof message,
        "Native audio: submitted %llu stereo frames, cumulative underruns %llu frames, overruns %llu frames (48 kHz)",
        (unsigned long long)g_audioSubmittedFrames,
        (unsigned long long)underrun, (unsigned long long)overrun);
      LOGI("%s", message);
      native_log_to_file("audio", message);
      g_audioLastDiagnosticNs = ns;
      g_audioSubmittedFrames = 0;
    }
  }
  return frames;
}

// --- status -------------------------------------------------------------------
static jboolean nativePauseMenuActive(JNIEnv *env, jobject thiz) {
  (void)env; (void)thiz;
  return PGPauseMenuActive() ? JNI_TRUE : JNI_FALSE;
}

static jint nativeLastInputDevice(JNIEnv *env, jobject thiz) {
  (void)env; (void)thiz;
  return PGLastInputDevice();
}

static jint nativeTouchContext(JNIEnv *env, jobject thiz) {
  (void)env; (void)thiz;
  return (jint)PGCurrentTouchContext();
}

static jint nativeFps(JNIEnv *env, jobject thiz) {
  (void)env; (void)thiz;
  return (jint)(PGRenderedFramesPerSecond() * 100.0);
}

// Both Android game modes display the same lock-free renderer snapshot.
// This is invoked at 1 Hz by the HUD, not by the 60 Hz input loop.
static jlongArray nativePerfStats(JNIEnv *env, jobject thiz) {
  (void)thiz;
  typedef void (*perf_snapshot_fn)(uint64_t*, size_t);
  uint64_t snapshot[6] = {0};
  perf_snapshot_fn read = (perf_snapshot_fn)pg_renderer_symbol("muguet_gpu_perf_snapshot");
  if (read) read(snapshot, 6);
  jlong data[6];
  for (int i = 0; i < 6; ++i) data[i] = (jlong)snapshot[i];
  jlongArray array = (*env)->NewLongArray(env, 6);
  if (array) (*env)->SetLongArrayRegion(env, array, 0, 6, data);
  return array;
}

// Set the engine heap ceiling (MB) before start. Must be called before PGStartEngine.
static void nativeSetHeapMb(JNIEnv *env, jobject thiz, jint mb) {
  (void)env; (void)thiz;
  char value[16];
  snprintf(value, sizeof value, "%d", (int)mb);
  setenv("PG_ENGINE_HEAP_MB", value, 1);
  LOGI("Engine heap set to %s MB", value);
}

jint RegisterEngineNatives(JNIEnv *env, jclass clazz);

// The JVM resolves this one by symbol name (static linkage), so it has to be
// exported under the exact JNI name. The other methods are bound dynamically by
// the table below - but that table can only be installed *by* this method, so
// this one cannot be registered dynamically.
JNIEXPORT jint JNICALL Java_dev_muguet_EngineActivity_nativeRegister(JNIEnv *env, jclass clazz) {
  return RegisterEngineNatives(env, clazz);
}

// --- JNI method table ---------------------------------------------------------
static JNINativeMethod g_methods[] = {
  {"nativeRegister",          "()I",                                  (void*)Java_dev_muguet_EngineActivity_nativeRegister},
  {"nativeCacheActivity",     "(Ljava/lang/Object;)Z",               (void*)nativeCacheActivity},
  {"nativeSetHeapMb",         "(I)V",                                 (void*)nativeSetHeapMb},
  {"nativeSetLogPath",        "(Ljava/lang/String;)V",                (void*)nativeSetLogPath},
  {"nativeConfigure",         "([Ljava/lang/String;IF[Ljava/lang/String;)V", (void*)nativeConfigure},
  {"nativeStart",             "(Ljava/lang/String;Ljava/lang/String;IIII)V", (void*)nativeStart},
  {"nativeAttachSurface",     "(Landroid/view/Surface;)V",            (void*)nativeAttachSurface},
  {"nativeUpdatePad",         "([Ljava/lang/String;IZFFZ)V",          (void*)nativeUpdatePad},
  {"nativeUpdatePadAnalog",   "(FFFFFFI)V",                            (void*)nativeUpdatePadAnalog},
  {"nativeSetTargetingMode", "(I)V",                                 (void*)nativeSetTargetingMode},
  {"nativeAudio",             "([SI)I",                                (void*)nativeAudio},
  {"nativePauseMenuActive",   "()Z",                                  (void*)nativePauseMenuActive},
  {"nativeLastInputDevice",   "()I",                                  (void*)nativeLastInputDevice},
  {"nativeTouchContext",      "()I",                                  (void*)nativeTouchContext},
  {"nativeFps",               "()I",                                  (void*)nativeFps},
  {"nativePerfStats",         "()[J",                                 (void*)nativePerfStats},
};

// Bind the table above onto the given class (dev.muguet.EngineActivity).
jint RegisterEngineNatives(JNIEnv *env, jclass clazz) {
  return (*env)->RegisterNatives(env, clazz, g_methods,
      sizeof g_methods / sizeof g_methods[0]);
}