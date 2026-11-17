# CoMER model files (not committed)

Run `python scripts/fetch_comer_models.py` from the project root. It downloads `encoder_int8.onnx`, `decoder_int8.onnx` and `vocab.json` from the
official ink-on release `v0.1.0`, verifies their SHA-256 digests, and places them here so they are packaged into the APK.
The app never downloads anything at run time. See `THIRD_PARTY_COMER.md` for the licence situation before distributing.
