#!/usr/bin/env python3
"""Developer-time fetch of the CoMER model files into the app's assets. The app itself never downloads anything.

    python scripts/fetch_comer_models.py                # downloads from the official ink-on release and verifies SHA-256
    python scripts/fetch_comer_models.py --from DIR     # copy from a local folder instead (still verified)

Source:  https://github.com/kimseungdae/ink-on/releases/tag/v0.1.0   (commit aaf79ca3722458cd8bf8f38de26a32d0e0b44d68)
Before shipping an app that contains these files, read THIRD_PARTY_COMER.md (the licence chain of the weights is NOT fully established).
"""
import argparse, hashlib, os, shutil, sys, urllib.request

RELEASE = "https://github.com/kimseungdae/ink-on/releases/download/v0.1.0/"
# name -> accepted SHA-256 digests. vocab.json is published with CRLF line endings in the release and LF in the git tree;
# the JSON content is identical, so both byte forms are accepted.
FILES = {
    "encoder_int8.onnx": {"272f17c7b120db61db6cd8d77e695d0139144044abd57d9b23284435a5d916fa"},
    "decoder_int8.onnx": {"658901d81ca3aa4b62b0da262e8fe67ebcfdac19cfb52ca2cfd076bebd481088"},
    "vocab.json": {"6ef75b588049981d2b5b2ceedcc7066dddcbb0c7d04ee2cbfb0ec861f192a850",   # release (CRLF)
                   "1d75cbbea3bed558683d7e9c3b3c72069ef2143a155d5f8c610f85a281af000c"},  # LF-normalised
}

def sha256(b): return hashlib.sha256(b).hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="src", help="folder that already contains the three files")
    ap.add_argument("--dest", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "models", "comer"))
    a = ap.parse_args()
    os.makedirs(a.dest, exist_ok=True)
    for name, ok in FILES.items():
        if a.src:
            data = open(os.path.join(a.src, name), "rb").read()
        else:
            print("downloading", RELEASE + name)
            with urllib.request.urlopen(RELEASE + name, timeout=120) as r:
                data = r.read()
        digest = sha256(data)
        if digest not in ok:
            sys.exit(f"CHECKSUM MISMATCH for {name}: got {digest}\nrefusing to install a file that is not the pinned release artifact.")
        with open(os.path.join(a.dest, name), "wb") as f:
            f.write(data)
        print(f"ok  {name}  {len(data):>8} bytes  sha256 {digest[:16]}...")
    print("installed into", a.dest)

if __name__ == "__main__":
    main()
