"""Mirror Wasmtime's explicitly unsupported __syscall_* behavior.

Wasmtime's host.rs returns -52 for unknown __syscall_* imports. The
native wasm2c adapter must not trap on those same offline-only calls,
nor silently report success or pretend networking works. Never apply
this fallback to arbitrary missing Emscripten/game imports.
"""


def offline_unsupported_syscall(module: str, name: str, result_type: str) -> bool:
    return module == "env" and name.startswith("__syscall_") and result_type in ("u32", "u64")


def native_offline_return(result_type: str) -> str:
    if result_type not in ("u32", "u64"):
        raise ValueError("Syscall fallback requires a numeric Wasm return type")
    return f"  return ({result_type})-52;"


def status_label(module: str, name: str, result_type: str) -> str:
    return (
        "offline ENOSYS(-52), matches Wasmtime host"
        if offline_unsupported_syscall(module, name, result_type)
        else "unported: stops the boot probe"
    )
