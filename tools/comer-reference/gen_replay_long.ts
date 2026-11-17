import { readFileSync, writeFileSync } from "fs";
import { gzipSync } from "zlib";
const { InferenceEngine } = await import("./src/inference_record.ts");
const { recorded } = await import("./src/ort-record.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const cases: Record<string, string> = { len20_long_sum_w1: "len20_long_sum_w1@1.0", len13_mixed_w1: "len13_12*34-56+78_w1@1.0" };
const expected: any[] = []; const perCase: Record<string, any[]> = {};
for (const [alias, file] of Object.entries(cases)) {
  const d = JSON.parse(readFileSync(`long_tensors/${file}.json`, "utf8"));
  const u8 = Buffer.from(d.tensor, "base64"); const tensor = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) tensor[i] = u8[i]! / 255;
  const mask = new Uint8Array(Buffer.from(d.mask, "base64")); recorded.clear();
  for (const beam of [1, 3]) {
    const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: beam, executionProvider: "cpu" as any });
    const r = await eng.recognize({ tensor, height: d.h, width: d.w, mask, maskHeight: d.h, maskWidth: d.w } as any, vocab, "auto");
    expected.push({ case: alias, beam, mode: "auto", latex: r.latex, tokenIds: r.tokenIds, width: d.w }); eng.dispose();
  }
  perCase[alias] = [...recorded.entries()].map(([k, v]) => ({ ids: k.split(",").map(Number), logits: Buffer.from(v.buffer, v.byteOffset, v.byteLength).toString("base64") }));
}
writeFileSync("golden/real_replay_long.json.gz", gzipSync(JSON.stringify({ vocabSize: 113, cases: perCase, expected })));
console.log(expected.map((e) => `${e.case} beam${e.beam} w=${e.width}: ${e.latex}`).join("\n"));
console.log("queries:", Object.values(perCase).map((e) => e.length).join(", "));
