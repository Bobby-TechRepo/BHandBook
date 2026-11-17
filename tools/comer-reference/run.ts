import { readFileSync, readdirSync, writeFileSync } from "fs";
import { gzipSync } from "zlib";
const { InferenceEngine } = await import("./src/inference.ts");
const { preprocessStrokes } = await import("./src/preprocessing.ts");
const { isStrokeMeaningful } = await import("./src/preprocessing_browser.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/");
const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));

// App-level rule (NOT reference code): stroke thickness is normalised so that, after the reference's own scaling, ink is TARGET_THICKNESS px thick.
const TARGET_THICKNESS = 3.0;
function lineWidthFor(strokes: { points: { x: number; y: number }[] }[]): number {
  let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
  for (const s of strokes) for (const p of s.points) { minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); }
  const rawW = Math.max(1, Math.ceil(maxX - minX)) + 32, rawH = Math.max(1, Math.ceil(maxY - minY)) + 32;
  const scale = Math.min(128 / rawH, 1024 / rawW);
  return Math.max(2, TARGET_THICKNESS / scale);
}

const files = readdirSync("fixtures").filter((f) => f.endsWith(".json")).sort();
const engines: Record<string, any> = {};
for (const b of [1, 2, 3]) engines[b] = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: b, executionProvider: "cpu" as any });

const out: any = { vocabSize: vocab.vocab_size, targetThickness: TARGET_THICKNESS, samples: {} };
for (const f of files) {
  const fx = JSON.parse(readFileSync("fixtures/" + f, "utf8"));
  const lw = lineWidthFor(fx.strokes.map((s: any) => ({ points: s.points.map((p: number[]) => ({ x: p[0], y: p[1] })) })));
  const strokes = fx.strokes.map((s: any) => ({ points: s.points.map((p: number[]) => ({ x: p[0], y: p[1] })), lineWidth: lw }));
  const meaningful = isStrokeMeaningful(strokes);
  const rec: any = { label: fx.label, lineWidth: lw, meaningful, results: {} };
  if (meaningful) {
    const pre = preprocessStrokes(strokes);
    rec.height = pre.height; rec.width = pre.width;
    // lossless: every tensor value is k/255 from an 8-bit canvas
    const u8 = new Uint8Array(pre.tensor.length); for (let i = 0; i < u8.length; i++) u8[i] = Math.round(pre.tensor[i] * 255);
    writeFileSync(`golden/${f.replace(".json", "")}.tensor.u8.gz`, gzipSync(Buffer.from(u8)));
    writeFileSync(`golden/${f.replace(".json", "")}.mask.u8.gz`, gzipSync(Buffer.from(pre.mask)));
    for (const b of [1, 2, 3]) {
      const r = await engines[b].recognize(pre, vocab, "auto");
      rec.results["beam" + b] = { latex: r.latex, tokenIds: r.tokenIds, encoderMs: r.encoderMs, decoderMs: r.decoderMs };
    }
  }
  out.samples[f.replace(".json", "")] = rec;
  const b3 = rec.results.beam3;
  console.log(f.padEnd(24), String(rec.label).padEnd(18), "| meaningful:", meaningful, "|", b3 ? `beam3: "${b3.latex}"  (${b3.encoderMs}+${b3.decoderMs}ms)` : "skipped");
}
writeFileSync("golden/reference_results.json", JSON.stringify(out, null, 1));
for (const b of [1, 2, 3]) engines[b].dispose();
