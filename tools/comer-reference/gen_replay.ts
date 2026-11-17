import { readFileSync, writeFileSync } from "fs";
import { gzipSync } from "zlib";
const { InferenceEngine } = await import("./src/inference_record.ts");
const { recorded } = await import("./src/ort-record.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const cases = ["add_25_15_w1@1.0", "frac_3_4_w2@1.0", "add_eq_w1@1.0", "eq_2x_w3@1.0"];
const expected: any[] = []; const perCase: Record<string, any[]> = {};
for (const c of cases) {
  const d = JSON.parse(readFileSync(`kotlin_tensors/${c}.json`, "utf8"));
  const u8 = Buffer.from(d.tensor, "base64"); const tensor = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) tensor[i] = u8[i]! / 255;
  const mask = new Uint8Array(Buffer.from(d.mask, "base64"));
  recorded.clear();
  for (const beam of [1, 2, 3]) for (const mode of ["auto", "number"]) {
    const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: beam, executionProvider: "cpu" as any });
    const r = await eng.recognize({ tensor, height: d.h, width: d.w, mask, maskHeight: d.h, maskWidth: d.w } as any, vocab, mode as any);
    expected.push({ case: c, beam, mode, latex: r.latex, tokenIds: r.tokenIds }); eng.dispose();
  }
  perCase[c] = [...recorded.entries()].map(([k, v]) => ({ ids: k.split(",").map(Number), logits: Buffer.from(v.buffer, v.byteOffset, v.byteLength).toString("base64") }));
}
writeFileSync("golden/real_replay.json.gz", gzipSync(JSON.stringify({ vocabSize: 113, cases: perCase, expected })));
console.log("recorded", Object.values(perCase).reduce((n, e) => n + e.length, 0), "decoder queries over", cases.length, "inputs for", expected.length, "decodes");
