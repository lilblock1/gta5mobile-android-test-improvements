// Native renderer-only crate: wasm2c C is the game engine; Rust is GPU + importer.
// No Wasmtime runtime, Cranelift, or JIT is linked into this APK variant.
// winit's Android-native-activity integration includes native_app_glue, which
// references android_main even in our Java SurfaceView renderer. Native-only
// builds do not use a NativeActivity, but the Android dynamic loader requires
// the symbol to exist before it can dlopen libmuguet.so. This entrypoint is
// deliberately never invoked in our SurfaceView-based activity.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn android_main(_app: *mut std::ffi::c_void) {}

#[path = "gpu.rs"] mod gpu;
#[path = "texture.rs"] mod texture;
#[path = "frame_queue.rs"] mod frame_queue;
#[path = "shader_fixes.rs"] mod shader_fixes;
#[path = "nightlane_render.rs"] mod nightlane_render;
#[path = "import.rs"] mod import;

pub fn runtime_memory_mb() -> u64 { 0 } // C engine owns game heap.
pub fn footprint_mb() -> u64 {
    std::fs::read_to_string("/proc/self/status").unwrap_or_default().lines()
        .find_map(|s| s.strip_prefix("VmRSS:")?.split_whitespace().next()?.parse::<u64>().ok())
        .unwrap_or(0) / 1024
}
pub fn set_interactive_qos() { /* Foreground thread scheduling belongs to Android. */ }
mod host { pub fn movie_diagnostics_snapshot() -> String { "native engine logs".into() } }
#[cfg(target_os = "android")]
mod android { pub fn status(message: &str) { eprintln!("[native-renderer] {message}"); } }

#[cfg(target_os = "android")]
mod launcher_jni {
    use anyhow::{Context, Result};
    use jni::objects::{JClass, JObject, JString, JValue};
    use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;
    use std::collections::VecDeque;
    use std::path::{Path, PathBuf};
    const BUILD: &str = "8b0b5899ed";

    fn valid(dir: &Path) -> bool {
        std::fs::File::open(dir.join("data/manifest.json")).is_ok()
            && std::fs::File::open(dir.join("b").join(BUILD).join("shaders/index.json")).is_ok()
    }
    fn find_root(dir: &Path) -> Option<PathBuf> {
        let mut paths = VecDeque::from([(dir.to_path_buf(), 0_usize)]);
        let mut visited = 0;
        while let Some((path, depth)) = paths.pop_front() {
            visited += 1;
            if visited > 512 { break; }
            if valid(&path) { return Some(path); }
            if depth >= 4 { continue; }
            if let Ok(children) = std::fs::read_dir(&path) {
                for entry in children.flatten() {
                    if !entry.file_type().map(|v| v.is_dir()).unwrap_or(false) { continue; }
                    let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
                    if ["data", "b", "x64", "common", "shaders", "vendor", "node_modules"]
                        .contains(&name.as_str()) { continue; }
                    paths.push_back((entry.path(), depth + 1));
                }
            }
        }
        None
    }
    fn game_dir(support: &Path) -> PathBuf {
        let configured = std::fs::read(support.join("config.json")).ok()
            .and_then(|data| serde_json::from_slice::<serde_json::Value>(&data).ok())
            .and_then(|cfg| cfg.get("game_folder_path")?.as_str().map(str::to_owned));
        if let Some(p) = configured {
            if !p.is_empty() && valid(Path::new(&p)) { return PathBuf::from(p); }
        }
        support.join("game")
    }
    fn throw(env: &mut JNIEnv, e: impl std::fmt::Display) {
        let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
    }
    #[no_mangle]
    pub extern "system" fn Java_dev_muguet_LauncherActivity_nativePrepare(
        mut env: JNIEnv, _class: JClass, dir: JString,
    ) -> jboolean {
        let result = (|| -> Result<bool> {
            let support: String = env.get_string(&dir)?.into();
            std::env::set_var("MUGUET_SUPPORT", &support);
            Ok(valid(&game_dir(Path::new(&support))))
        })();
        match result { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE,
            Err(e) => { throw(&mut env, e); JNI_FALSE } }
    }
    #[no_mangle]
    pub extern "system" fn Java_dev_muguet_LauncherActivity_nativeGameRoot(
        mut env: JNIEnv, _activity: JObject, dir: JString,
    ) -> jstring {
        let path: String = match env.get_string(&dir) { Ok(v) => v.into(),
            Err(_) => return std::ptr::null_mut() };
        let Some(path) = find_root(Path::new(&path)) else { return std::ptr::null_mut() };
        match env.new_string(path.to_string_lossy().as_ref()) {
            Ok(v) => v.into_raw(), Err(_) => std::ptr::null_mut(),
        }
    }
    #[no_mangle]
    pub extern "system" fn Java_dev_muguet_LauncherActivity_nativeImport(
        mut env: JNIEnv, activity: JObject, archive: JString,
    ) {
        let result = (|| -> Result<()> {
            let source: String = env.get_string(&archive)?.into();
            let support = PathBuf::from(std::env::var_os("MUGUET_SUPPORT")
                .context("Prepare game storage before importing")?);
            super::import::import(Path::new(&source), &support.join("game"), true, |done, total| {
                let _ = env.call_method(&activity, "importProgress", "(JJ)V",
                    &[JValue::Long(done as i64), JValue::Long(total as i64)]);
            })
        })();
        if let Err(e) = result { throw(&mut env, format!("{e:#}")); }
    }
}
