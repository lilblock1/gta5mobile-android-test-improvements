"""Offline wasm2c syscall fallback must match Wasmtime and never claim success."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "app" / "Scripts" / "offline_syscalls.py"
spec = importlib.util.spec_from_file_location("offline_syscalls_test", SCRIPT)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class NativeOfflineSyscallsTest(unittest.TestCase):
    SOCKETS = (
        "__syscall_accept4", "__syscall_connect", "__syscall_getsockname",
        "__syscall_getsockopt", "__syscall_poll", "__syscall_recvfrom",
        "__syscall_sendto", "__syscall_setsockopt", "__syscall_shutdown",
        "__syscall_socket",
    )

    def test_unimplemented_offline_socket_imports_match_wasmtime_error(self):
        for syscall in self.SOCKETS:
            with self.subTest(syscall=syscall):
                self.assertTrue(policy.offline_unsupported_syscall("env", syscall, "u32"))
                self.assertEqual(policy.native_offline_return("u32"), "  return (u32)-52;")
                self.assertIn("matches Wasmtime", policy.status_label("env", syscall, "u32"))

    def test_other_imports_must_not_be_stubbed(self):
        for module, name, result in (
            ("env", "wasm_print_line_js", "u32"),
            ("env", "__pthread_create_js", "u32"),
            ("env", "wgpu_start_worker", "void"),
            ("game", "__syscall_socket", "u32"),
            ("env", "__syscall_socket", "void"),
            ("env", "__syscall_socket", "f32"),
        ):
            with self.subTest(module=module, name=name, result=result):
                self.assertFalse(policy.offline_unsupported_syscall(module, name, result))
                self.assertIn("unported", policy.status_label(module, name, result))

    def test_numeric_return_types_only(self):
        self.assertEqual(policy.native_offline_return("u64"), "  return (u64)-52;")
        for invalid in ("void", "i32", "f32", "", "u32) && 1"):
            with self.subTest(result=invalid), self.assertRaises(ValueError):
                policy.native_offline_return(invalid)


if __name__ == "__main__":
    unittest.main()
