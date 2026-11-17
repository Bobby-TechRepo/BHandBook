# CoMER / ink-on model: provenance, licence and attribution

## What is used

| Item | Value |
|---|---|
| Source repository | https://github.com/kimseungdae/ink-on |
| Release | `v0.1.0` |
| Source commit | `aaf79ca3722458cd8bf8f38de26a32d0e0b44d68` |
| `encoder_int8.onnx` | 3,460,779 bytes, SHA-256 `272f17c7b120db61db6cd8d77e695d0139144044abd57d9b23284435a5d916fa` |
| `decoder_int8.onnx` | 4,118,047 bytes, SHA-256 `658901d81ca3aa4b62b0da262e8fe67ebcfdac19cfb52ca2cfd076bebd481088` |
| `vocab.json` | 3,934 bytes, SHA-256 `6ef75b588049981d2b5b2ceedcc7066dddcbb0c7d04ee2cbfb0ec861f192a850` (release copy, CRLF) |
| Vocabulary size | **113** tokens (the README's "245" is wrong; the file, the decoder's logits dimension and the release notes all say 113) |

The three files were checked to come from the same release: encoder and decoder are byte-identical to the copies in the repository's `main` branch,
and `vocab.json` differs only in line endings. Full detail, including the ONNX graph interface, is in `docs/comer/model-manifest.json`
(and a unit test keeps that file, `scripts/fetch_comer_models.py` and the Android loader consistent).

## Licence status: READ BEFORE DISTRIBUTING AN APP THAT CONTAINS THESE FILES

* **ink-on** is licensed under **Apache-2.0**, Copyright 2025 kimseungdae. That grant covers the ink-on source code and what its author is entitled to license.
* The model weights are derived from **CoMER**: *CoMER: Modeling Coverage for Transformer-based Handwritten Mathematical Expression Recognition*,
  Wenqi Zhao and Liangcai Gao, ECCV 2022 (https://github.com/Green-Wood/CoMER). At the time of checking, that repository **has no LICENSE file**,
  so it grants no explicit rights to pass on. It also distributes **CROHME** handwriting data, which has its own terms.
* ink-on publishes **no model card**: it does not say which checkpoint was converted, who quantised it, or under what terms the training data was used.

Therefore the **redistribution rights for the weights are not established**, and this project **does not contain the model binaries**
(`*.onnx` is git-ignored). `scripts/fetch_comer_models.py` downloads the official release files and verifies the checksums above so that you can build
and test locally. Before publishing an app that bundles them, resolve the chain of title, for example by asking the ink-on author for the training
provenance and the CoMER authors for a licence statement, or by training/quantising your own model. The recognition code is model-agnostic
(`MathRecognitionEngine`), so a different compatible model can replace these files.

## Required notices (when the files are bundled)

* ink-on, Copyright 2025 kimseungdae, Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0). Preserve this notice and the licence text.
* The ONNX Runtime Android package is MIT-licensed (Microsoft); keep its notices (shipped inside the AAR).
* The recognition pipeline in `core/comer` is a Kotlin port of the framework-independent parts of ink-on (`preprocessing.ts`, `inference.ts`,
  `latex-repair.ts`, `tokenizer.ts`). Their behaviour was verified against the original TypeScript (see `tools/comer-reference`).
