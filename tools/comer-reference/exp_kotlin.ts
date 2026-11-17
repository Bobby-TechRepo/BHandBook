import { readFileSync, readdirSync } from "fs";
const { InferenceEngine } = await import("./src/inference.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: 3, executionProvider: "cpu" as any });
const squash = (s: string) => s.replace(/\s+/g, "");
const collapse = (s: string) => s.replace(/(=\s*)+=/g, "=");        // the app's documented "= =" rule
const bySize: Record<string, [number, number, number]> = {}; const wrong: string[] = [];
let ms = 0;
for (const f of readdirSync("kotlin_tensors").sort()) {
  const d = JSON.parse(readFileSync("kotlin_tensors/" + f, "utf8"));
  const u8 = Buffer.from(d.tensor, "base64"); const tensor = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) tensor[i] = u8[i]! / 255;
  const mask = new Uint8Array(Buffer.from(d.mask, "base64"));
  const t0 = performance.now();
  const r = await eng.recognize({ tensor, height: d.h, width: d.w, mask, maskHeight: d.h, maskWidth: d.w } as any, vocab, "auto");
  ms += performance.now() - t0;
  const size = f.split("@")[1]!.replace(".json", ""); bySize[size] ??= [0, 0, 0];
  bySize[size][2]++;
  if (squash(r.latex) === squash(d.label)) bySize[size][0]++;
  if (squash(collapse(r.latex)) === squash(d.label)) bySize[size][1]++; else wrong.push(`${f} expected ${squash(d.label)} got ${squash(r.latex)}`);
}
console.log("Kotlin-preprocessed inputs -> REAL encoder/decoder (beam 3)\n");
console.log("size   exact   exact-after-'= =' collapse");
for (const [s, [a, b, n]] of Object.entries(bySize)) console.log(`${s.padEnd(5)}  ${a}/${n}     ${b}/${n}`);
console.log("\nmisreads after collapse:"); wrong.forEach((w) => console.log("  ", w));
console.log("\navg recognise time per sample:", (ms / readdirSync("kotlin_tensors").length).toFixed(0), "ms (x86 node, 1 thread)");
