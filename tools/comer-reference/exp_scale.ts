import { readFileSync, readdirSync } from "fs";
const { InferenceEngine } = await import("./src/inference.ts");
const { preprocessStrokes } = await import("./src/preprocessing.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/");
const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: 3, executionProvider: "cpu" as any });
const files = readdirSync("fixtures").filter((f) => f.endsWith(".json") && !f.startsWith("junk")).sort();
const T = 3.0;
function lineWidthFor(strokes: any[]) {
  let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
  for (const s of strokes) for (const p of s.points) { minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); }
  const rawW = Math.max(1, Math.ceil(maxX - minX)) + 32, rawH = Math.max(1, Math.ceil(maxY - minY)) + 32;
  return Math.max(2, T / Math.min(128 / rawH, 1024 / rawW));
}
function bbox(strokes: any[]) { let a = 1e9, b = 1e9, c = -1e9, d = -1e9; for (const s of strokes) for (const p of s.points) { a = Math.min(a, p.x); b = Math.min(b, p.y); c = Math.max(c, p.x); d = Math.max(d, p.y); } return { minX: a, minY: b, w: c - a, h: d - b }; }
const norm = (s: string) => s.replace(/\s+/g, "").replace(/= =/g, "=").replace(/==/g, "=");
const modes: Record<string, number | null> = { "H=48": 48, "H=64": 64, "H=80": 80, "H=96": 96, "H=112": 112 };
const sizes = [1];
const table: Record<string, Record<number, [number, number]>> = {};
for (const [mname, canon] of Object.entries(modes)) { table[mname] = {}; for (const f of sizes) table[mname][f] = [0, 0]; }
for (const f of files) {
  const fx = JSON.parse(readFileSync("fixtures/" + f, "utf8")); const want = norm(String(fx.label));
  for (const size of sizes) for (const [mname, canon] of Object.entries(modes)) {
    let strokes = fx.strokes.map((s: any) => ({ points: s.points.map((p: number[]) => ({ x: p[0] * size, y: p[1] * size })) }));
    if (canon) { const b = bbox(strokes); const basis = Math.max(b.h, b.w * 0.1, 1); const k = canon / basis; strokes = strokes.map((s: any) => ({ points: s.points.map((p: any) => ({ x: (p.x - b.minX) * k, y: (p.y - b.minY) * k })) })); }
    const lw = lineWidthFor(strokes); strokes = strokes.map((s: any) => ({ ...s, lineWidth: lw }));
    const pre = preprocessStrokes(strokes); const r = await eng.recognize(pre, vocab, "auto");
    const ok = norm(r.latex) === want; table[mname][size][0] += ok ? 1 : 0; table[mname][size][1]++;
  }
}
console.log("exact-match over", files.length, "fixtures (3 writers x 10 expressions); rows = strategy, columns = writing size relative to 1x");
console.log("strategy".padEnd(28), sizes.map((s) => (s + "x").padStart(8)).join(""));
for (const [m, row] of Object.entries(table)) console.log(m.padEnd(28), sizes.map((s) => `${row[s][0]}/${row[s][1]}`.padStart(8)).join(""));
