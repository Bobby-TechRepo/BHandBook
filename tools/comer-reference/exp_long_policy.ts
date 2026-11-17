import { readFileSync, readdirSync } from "fs";
const { InferenceEngine } = await import("./src/inference.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const mk = (b: number) => new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: b, executionProvider: "cpu" as any });
const e1 = mk(1), e3 = mk(3);
const squash = (s: string) => s.replace(/\s+/g, ""); const collapse = (s: string) => s.replace(/(=\s*)+=/g, "=");
const stripTail = (latex: string) => { const t = latex.trim().split(/\s+/); const i = t.lastIndexOf("="); return i >= 0 && i < t.length - 1 ? t.slice(0, i + 1).join(" ") : latex; };
const stat: Record<string, { base: number; pol: number; n: number; ms0: number; ms1: number }> = {};
let b = 0, p = 0, n = 0, t0 = 0, t1 = 0;
const files = [...readdirSync("long_tensors"), ...readdirSync("space_tensors").map((f) => "../space_tensors/" + f)].filter((f) => !f.startsWith("../space")).sort();
for (const f of files) {
  const d = JSON.parse(readFileSync("long_tensors/" + f, "utf8"));
  const u8 = Buffer.from(d.tensor, "base64"); const tensor = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) tensor[i] = u8[i]! / 255;
  const input = { tensor, height: d.h, width: d.w, mask: new Uint8Array(Buffer.from(d.mask, "base64")), maskHeight: d.h, maskWidth: d.w } as any;
  const base = await e3.recognize(input, vocab, "auto");                                   // before: beam 3 always, no tail rule
  const pol = await (d.w >= 768 ? e1 : e3).recognize(input, vocab, "auto");                 // after: adaptive beam
  const label = squash(d.label), inkEq = label.endsWith("=");
  const okBase = squash(collapse(base.latex)) === label;
  const okPol = squash(collapse(inkEq ? stripTail(collapse(pol.latex)) : collapse(pol.latex))) === label;
  const key = f.replace(/_w\d@.*/, ""); const s = (stat[key] ??= { base: 0, pol: 0, n: 0, ms0: 0, ms1: 0 });
  s.n++; s.base += okBase ? 1 : 0; s.pol += okPol ? 1 : 0; s.ms0 += base.encoderMs + base.decoderMs; s.ms1 += pol.encoderMs + pol.decoderMs;
  b += okBase ? 1 : 0; p += okPol ? 1 : 0; n++; t0 += base.encoderMs + base.decoderMs; t1 += pol.encoderMs + pol.decoderMs;
}
console.log("expression".padEnd(24), "before".padStart(7), "after".padStart(7), "ms before".padStart(10), "ms after".padStart(9));
for (const [k, s] of Object.entries(stat)) console.log(k.padEnd(24), `${s.base}/${s.n}`.padStart(7), `${s.pol}/${s.n}`.padStart(7), (s.ms0 / s.n).toFixed(0).padStart(10), (s.ms1 / s.n).toFixed(0).padStart(9));
console.log(`\nTOTAL before ${b}/${n} (${(100 * b / n).toFixed(1)}%)  after ${p}/${n} (${(100 * p / n).toFixed(1)}%) | mean latency ${(t0 / n).toFixed(0)} ms -> ${(t1 / n).toFixed(0)} ms`);
