# CoMER reference harness

Runs the **unmodified ink-on TypeScript** (preprocessing, inference, LaTeX repair, tokenizer) on Node with the real models, and produces the golden data
the Kotlin port is tested against (`app/src/test/resources/comer/`).

* `src/` holds verbatim copies of ink-on files (Apache-2.0, `src/LICENSE-ink-on.txt`). The only edits are two import lines in `inference.ts`
  (`onnxruntime-web` -> `./ort-shim`, the browser model cache -> `./model-cache-shim`); `inference_fake.ts` / `inference_record.ts` swap the same
  imports for a scripted / recording runtime.
* Needs the model files: `python scripts/fetch_comer_models.py` (see `THIRD_PARTY_COMER.md` for the licence situation), or set `COMER_MODELS_DIR`.

```
cd tools/comer-reference && npm install
npm run reference     # real models on fixtures/ -> golden/reference_results.json (+ tensors)
npm run goldens       # repair / completeness / decode (scripted logits) / resample / meaningfulness goldens
npm run replay        # records real decoder logits for the Kotlin replay test
npm run scale         # writing-size experiment that chose canonicalHeight = 80
npm run bench         # latency and memory on this machine
# stress study (long expressions): generate fixtures with kotlin/GenerateLongFixtures.kt, dump tensors with kotlin/DumpKotlinTensors.kt into long_tensors/, then
npx tsx exp_long.ts           # accuracy / width / tokens / latency per expression
npx tsx exp_long_policy.ts    # the same set with the adaptive-beam and tail-after-"=" rules applied
npx tsx gen_replay_long.ts    # records real logits for the long-expression Kotlin tests
```
`kotlin/GenerateFixtures.kt` writes the stroke fixtures and `kotlin/DumpKotlinTensors.kt` dumps the tensors the Kotlin preprocessor produces so they can be
pushed through the real models (`exp_kotlin.ts`). Compile them with `kotlinc` together with `app/src/main/java/**/core/**` and `domain/**`
(plus `app/src/test/.../core/recognition/HandwritingFixtures.kt` for the generator).
