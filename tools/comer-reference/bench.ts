import { readFileSync } from "fs";
const { InferenceEngine } = await import("./src/inference.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const rss = () => Math.round(process.memoryUsage().rss / 1048576);
const load = (n: string) => { const d = JSON.parse(readFileSync(`kotlin_tensors/${n}.json`, "utf8")); const u8 = Buffer.from(d.tensor, "base64"); const t = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) t[i] = u8[i]! / 255; return { tensor: t, height: d.h, width: d.w, mask: new Uint8Array(Buffer.from(d.mask, "base64")), maskHeight: d.h, maskWidth: d.w, name: n }; };
const inputs = ["add_25_15_w1@1.0", "eq_2x_w1@1.0", "frac_3_4_w1@1.0", "sqrt_144_w1@1.0"].map(load);
console.log("input widths:", inputs.map((i) => `${i.name.split("@")[0]}=${i.width}px`).join(", "));
console.log("RSS before models:", rss(), "MB");
for (const beam of [1, 2, 3]) {
  const t0 = performance.now();
  const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: beam, executionProvider: "cpu" as any });
  await eng.recognize(inputs[0] as any, vocab, "auto");               // first call = session creation + warm-up
  const initMs = performance.now() - t0;
  const enc: number[] = [], dec: number[] = [];
  for (let k = 0; k < 6; k++) for (const inp of inputs) { const r = await eng.recognize(inp as any, vocab, "auto"); enc.push(r.encoderMs); dec.push(r.decoderMs); }
  const avg = (a: number[]) => (a.reduce((s, x) => s + x, 0) / a.length).toFixed(0);
  const p95 = (a: number[]) => [...a].sort((x, y) => x - y)[Math.floor(a.length * 0.95)]!.toFixed(0);
  console.log(`beam ${beam}: init+first ${initMs.toFixed(0)} ms | encoder avg ${avg(enc)} ms | decoder avg ${avg(dec)} ms | total avg ${(Number(avg(enc)) + Number(avg(dec))).toFixed(0)} ms (p95 enc+dec ≈ ${(Number(p95(enc)) + Number(p95(dec)))} ms) | RSS ${rss()} MB`);
  eng.dispose();
}
const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: 3, executionProvider: "cpu" as any });
await eng.recognize(inputs[0] as any, vocab, "auto"); const base = rss();
for (let i = 0; i < 150; i++) await eng.recognize(inputs[i % 4] as any, vocab, "auto");
console.log(`repeated recognition: RSS ${base} MB -> ${rss()} MB after 150 more runs`);
