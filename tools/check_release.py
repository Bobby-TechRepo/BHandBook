#!/usr/bin/env python3
"""
Checks a built RELEASE APK (and R8's mapping file) for the problems that only show up after shrinking.

    python tools/check_release.py                      # looks in app/build/outputs
    python tools/check_release.py --apk X.apk --mapping mapping.txt --max-mb 60

Exit status 0: nothing wrong (warnings are fine); 1: at least one FAIL.
"""
import argparse, glob, os, re, sys, zipfile

REQUIRED_KEPT = ["ai.onnxruntime.OrtEnvironment", "ai.onnxruntime.OrtSession", "ai.onnxruntime.OnnxTensor"]
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a"}


def check_mapping(text):
    """R8 writes 'original.Name -> renamed.Name:' for every class; a class whose two names differ was renamed."""
    mapped = dict(re.findall(r"^(\S+) -> (\S+):$", text, re.M))
    results = []
    if not mapped:
        return [("FAIL", "mapping file has no class entries (is this a release mapping.txt?)")]
    for name in REQUIRED_KEPT:
        if name not in mapped:
            results.append(("FAIL", f"{name} is not in the mapping: it was removed by shrinking (the ONNX Runtime keep rules are not applied)"))
        elif mapped[name] != name:
            results.append(("FAIL", f"{name} was renamed to {mapped[name]}: ONNX Runtime's native code looks it up by name, so recognition would fail in this build"))
        else:
            results.append(("OK", f"{name} kept"))
    return results


def check_apk(path, max_mb):
    results = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        infos = {i.filename: i for i in z.infolist()}
        size_mb = os.path.getsize(path) / 1e6
        results.append(("OK" if size_mb <= max_mb else "FAIL", f"APK size {size_mb:.1f} MB (budget {max_mb:g} MB)"))
        if not any(n.startswith("classes") and n.endswith(".dex") for n in names):
            results.append(("FAIL", "no classes.dex"))
        abis = {n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2}
        if not abis:
            results.append(("FAIL", "no native libraries at all: ONNX Runtime is missing"))
        else:
            for abi in sorted(abis):
                has = any(n.startswith(f"lib/{abi}/") and "onnxruntime" in n for n in names)
                results.append(("OK" if has else "FAIL", f"{abi}: ONNX Runtime native library {'present' if has else 'MISSING'}"))
            extra = abis - EXPECTED_ABIS
            if extra:
                results.append(("WARN", f"unexpected CPU types {sorted(extra)}: the ABI filter is not applied (larger download)"))
            if not (abis & EXPECTED_ABIS):
                results.append(("FAIL", "neither arm64-v8a nor armeabi-v7a is included"))
        results.append(("OK" if any(n.endswith(".ttf") for n in names) else "FAIL", "Handlee font " + ("present" if any(n.endswith(".ttf") for n in names) else "MISSING")))
        results.append(("OK" if "assets/licenses/NOTICE.txt" in names else "FAIL", "licence notices " + ("present" if "assets/licenses/NOTICE.txt" in names else "MISSING")))
        models = [n for n in names if n.endswith(".onnx")]
        if not models:
            results.append(("WARN", "no CoMER model files in this APK: the app will use the built-in geometric recogniser (see docs/RELEASE.md)"))
        for m in models:
            results.append(("OK" if infos[m].compress_type == zipfile.ZIP_STORED else "WARN", f"{m} {'stored uncompressed' if infos[m].compress_type == zipfile.ZIP_STORED else 'is compressed (slower to load)'}"))
        if "AndroidManifest.xml" in names:
            manifest = z.read("AndroidManifest.xml")
            needle = b"android.permission.INTERNET"
            if needle in manifest or needle.decode().encode("utf-16-le") in manifest:
                results.append(("FAIL", "the INTERNET permission is present: this app promises to make no network connections"))
            else:
                results.append(("OK", "no INTERNET permission"))
        signed = any(n.startswith("META-INF/") and (n.endswith(".RSA") or n.endswith(".EC") or n.endswith(".DSA")) for n in names) or any(n.startswith("META-INF/") and n.endswith(".SF") for n in names)
        results.append(("OK" if signed else "WARN", "signed (v1)" if signed else "no v1 signature (fine if it is v2/v3 signed; check with apksigner verify)"))
    return results


def newest(patterns):
    files = [f for p in patterns for f in glob.glob(p, recursive=True)]
    return max(files, key=os.path.getmtime) if files else None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk"); ap.add_argument("--mapping"); ap.add_argument("--max-mb", type=float, default=60.0)
    a = ap.parse_args(argv)
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    apk = a.apk or newest([os.path.join(root, "app/build/outputs/apk/release/*.apk")])
    mapping = a.mapping or newest([os.path.join(root, "app/build/outputs/mapping/release/mapping.txt")])
    results = []
    if mapping and os.path.exists(mapping): results += check_mapping(open(mapping, encoding="utf-8", errors="replace").read())
    else: results.append(("FAIL", "no mapping.txt found: build with ./gradlew assembleRelease first (or pass --mapping)"))
    if apk and os.path.exists(apk): results += check_apk(apk, a.max_mb)
    else: results.append(("FAIL", "no release APK found: build with ./gradlew assembleRelease first (or pass --apk)"))
    for level, msg in results: print(f"{level:5} {msg}")
    failed = sum(1 for l, _ in results if l == "FAIL")
    print(f"\n{failed} failure(s), {sum(1 for l, _ in results if l == 'WARN')} warning(s)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
