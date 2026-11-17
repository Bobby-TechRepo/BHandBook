import io, os, tempfile, unittest, zipfile
import check_release as c

GOOD_MAP = "ai.onnxruntime.OrtEnvironment -> ai.onnxruntime.OrtEnvironment:\n    void close() -> close\nai.onnxruntime.OrtSession -> ai.onnxruntime.OrtSession:\nai.onnxruntime.OnnxTensor -> ai.onnxruntime.OnnxTensor:\ncom.bsoft.bhandbook.MainActivity -> a.b:\n"

def apk(files, compressed=()):
    f = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
    with zipfile.ZipFile(f, "w") as z:
        for n, d in files.items(): z.writestr(zipfile.ZipInfo(n), d, compress_type=zipfile.ZIP_DEFLATED if n in compressed else zipfile.ZIP_STORED)
    return f.name

BASE = {"classes.dex": b"x", "lib/arm64-v8a/libonnxruntime.so": b"x", "lib/armeabi-v7a/libonnxruntime.so": b"x", "res/ab.ttf": b"x",
        "assets/licenses/NOTICE.txt": b"x", "AndroidManifest.xml": b"manifest", "META-INF/CERT.RSA": b"x"}
def levels(r): return [l for l, _ in r]

class Mapping(unittest.TestCase):
    def test_kept(self): self.assertEqual(levels(c.check_mapping(GOOD_MAP)), ["OK", "OK", "OK"])
    def test_renamed_fails(self): self.assertIn("FAIL", levels(c.check_mapping(GOOD_MAP.replace("ai.onnxruntime.OrtSession -> ai.onnxruntime.OrtSession:", "ai.onnxruntime.OrtSession -> a.c:"))))
    def test_removed_fails(self): self.assertIn("FAIL", levels(c.check_mapping(GOOD_MAP.replace("ai.onnxruntime.OnnxTensor -> ai.onnxruntime.OnnxTensor:\n", ""))))
    def test_empty_fails(self): self.assertEqual(levels(c.check_mapping("")), ["FAIL"])

class Apk(unittest.TestCase):
    def run_(self, files, **kw): p = apk(files, **kw); self.addCleanup(os.unlink, p); return c.check_apk(p, 60)
    def test_good_apk_has_no_failures_and_one_models_warning(self):
        r = self.run_(BASE); self.assertNotIn("FAIL", levels(r)); self.assertEqual(levels(r).count("WARN"), 1)
    def test_models_stored_uncompressed_is_ok(self): self.assertNotIn("WARN", levels(self.run_({**BASE, "assets/models/comer/encoder.onnx": b"x"})))
    def test_compressed_models_warn(self): self.assertIn("WARN", levels(self.run_({**BASE, "assets/models/comer/encoder.onnx": b"x"}, compressed={"assets/models/comer/encoder.onnx"})))
    def test_missing_native_library_fails(self):
        files = {k: v for k, v in BASE.items() if "libonnxruntime" not in k}; self.assertIn("FAIL", levels(self.run_(files)))
    def test_missing_one_abi_library_fails(self): self.assertIn("FAIL", levels(self.run_({k: v for k, v in BASE.items() if k != "lib/armeabi-v7a/libonnxruntime.so"} | {"lib/armeabi-v7a/other.so": b"x"})))
    def test_extra_cpu_types_warn(self): self.assertIn("WARN", levels(self.run_({**BASE, "lib/x86/libonnxruntime.so": b"x"})))
    def test_internet_permission_fails(self):
        self.assertIn("FAIL", levels(self.run_({**BASE, "AndroidManifest.xml": b"..android.permission.INTERNET.."})))
        self.assertIn("FAIL", levels(self.run_({**BASE, "AndroidManifest.xml": "android.permission.INTERNET".encode("utf-16-le")})))
    def test_missing_font_or_notices_fail(self):
        self.assertIn("FAIL", levels(self.run_({k: v for k, v in BASE.items() if not k.endswith(".ttf")}))); self.assertIn("FAIL", levels(self.run_({k: v for k, v in BASE.items() if "NOTICE" not in k})))
    def test_size_budget(self):
        p = apk(BASE); self.addCleanup(os.unlink, p); self.assertIn("FAIL", levels(c.check_apk(p, 0.0000001)))
    def test_main_reports_missing_files(self): self.assertEqual(c.main(["--apk", "/nonexistent.apk", "--mapping", "/nonexistent.txt"]), 1)

if __name__ == "__main__":
    unittest.main()
