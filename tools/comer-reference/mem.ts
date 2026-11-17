import { readFileSync, readdirSync } from "fs";
const { InferenceEngine } = await import("./src/inference.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const rss = () => Math.round(process.memoryUsage().rss / 1048576);
const files = readdirSync("kotlin_tensors").filter((f: string) => f.includes("@1.0") || f.includes("@2.5") || f.includes("@0.4")).sort();
const load = (n: string) => { const d = JSON.parse(readFileSync(`kotlin_tensors/${n}`, "utf8")); const u8 = Buffer.from(d.tensor, "base64"); const t = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) t[i] = u8[i]! / 255; return { tensor: t, height: d.h, width: d.w, mask: new Uint8Array(Buffer.from(d.mask, "base64")), maskHeight: d.h, maskWidth: d.w }; };
const inputs = files.map(load);
const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: 3, executionProvider: "cpu" as any });
for (let i = 0; i < 30; i++) await eng.recognize(inputs[i % inputs.length] as any, vocab, "auto");     // warm every shape once
(globalThis as any).gc?.(); const warm = rss(); const marks: number[] = [];
for (let i = 1; i <= 400; i++) { await eng.recognize(inputs[i % inputs.length] as any, vocab, "auto"); if (i % 100 === 0) { (globalThis as any).gc?.(); marks.push(rss()); } }
console.log(`tweak=${process.env.ORT_TWEAK === "1"} widths=${new Set(inputs.map((x: any) => x.width)).size} | RSS after warm-up ${warm} MB -> every 100 runs: ${marks.join(" -> ")} MB`);
