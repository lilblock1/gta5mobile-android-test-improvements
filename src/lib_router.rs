// Native-only builds keep the same Vulkan renderer, not the Wasmtime engine.
// The original app remains buildable from default Cargo features.
#[cfg(all(feature = "native-renderer", feature = "jit"))]
compile_error!("native-renderer and jit are mutually exclusive");
#[cfg(feature = "native-renderer")]
include!("renderer_only.rs");
#[cfg(not(feature = "native-renderer"))]
include!("lib.rs");
