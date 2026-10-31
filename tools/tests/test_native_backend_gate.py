"""Native ARM64 stage-1 gate: pin the input and preserve Wasmtime as default.

These tests intentionally do not need Android SDK, proprietary game assets,
native generated game code, or external downloads.
"""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PREP = ROOT / "app" / "Scripts" / "prepare_android.py"
spec = importlib.util.spec_from_file_location("nightlane_prepare_android", PREP)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class NativeBackendBuildGate(unittest.TestCase):
    def test_legacy_exact_engine_is_still_supported(self):
        self.assertEqual(prepare.verify_engine_digest(prepare.PINNED_WASM_SHA256), "legacy")
        self.assertEqual(
            prepare.verify_engine_digest(prepare.PINNED_WASM_SHA256, allow_bundled=True),
            "legacy",
        )

    def test_bundled_engine_is_opt_in_only(self):
        with self.assertRaises(ValueError):
            prepare.verify_engine_digest(prepare.BUNDLED_WASM_SHA256)
        self.assertEqual(
            prepare.verify_engine_digest(prepare.BUNDLED_WASM_SHA256, allow_bundled=True),
            "bundled-experimental",
        )

    def test_unverified_engine_is_never_accepted(self):
        for fingerprint in ("", "0" * 64, prepare.BUNDLED_WASM_SHA256[:-1] + "f"):
            with self.subTest(fingerprint=fingerprint):
                with self.assertRaises(ValueError):
                    prepare.verify_engine_digest(fingerprint, allow_bundled=True)

    def test_bundle_verifier_pin_matches_opt_in_engine(self):
        namespace = {}
        source = (ROOT / "tools" / "engine_resources.py").read_text()
        # The two fingerprint constants must not diverge silently. No asset
        # loading needed for this check.
        import re
        pin = re.search(r'^ENGINE_SHA256 = "([0-9a-f]{64})"$', source, re.M)
        self.assertIsNotNone(pin)
        self.assertEqual(pin.group(1), prepare.BUNDLED_WASM_SHA256)

    def test_native_host_and_renderer_seam_are_present(self):
        host = ROOT / "vendor" / "fivestar"
        for name in (
            "EngineHost.c", "EngineBridge.c", "NativeRenderer.c",
            "PGGraphicsHeadless.c", "WasmFutex.c",
        ):
            with self.subTest(name=name):
                self.assertTrue((host / name).is_file())
        c = (host / "EngineHost.c").read_text()
        self.assertIn('pg_renderer_symbol("muguet_gpu_start")', c)
        self.assertIn("PGGraphicsWait", c)
        self.assertIn("PGGraphicsNotify", c)
        rust = (ROOT / "src" / "gpu.rs").read_text()
        self.assertIn("fn muguet_gpu_start", rust)
        self.assertIn("fn muguet_gpu_attach_window", rust)

    def test_codegen_patchers_still_enforce_exact_body_signatures(self):
        pad = (ROOT / "app" / "Scripts" / "prepare_virtual_pad.py").read_text()
        mem = (ROOT / "app" / "Scripts" / "prepare_memory_pressure.py").read_text()
        self.assertIn("Unexpected ioPad::Update body", pad)
        self.assertIn("Pinned ioPad::Update changed", pad)
        self.assertIn("Pinned available-memory query changed", mem)
        self.assertIn("Pinned streaming-memory query changed", mem)


if __name__ == "__main__":
    unittest.main()
