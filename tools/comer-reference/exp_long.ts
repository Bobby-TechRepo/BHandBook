import { readFileSync, readdirSync } from "fs";
const { InferenceEngine } = await import("./src/inference.ts");
const M = (process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/"); const vocab = JSON.parse(readFileSync(M + "vocab.json", "utf8"));
const eng = new InferenceEngine({ encoderUrl: M + "encoder_int8.onnx", decoderUrl: M + "decoder_int8.onnx", beamWidth: 3, executionProvider: "cpu" as any });
const squash = (s: string) => s.replace(/\s+/g, ""); const collapse = (s: string) => s.replace(/(=\s*)+=/g, "=");
type Row = { name: string; ok: number; n: number; w: number; tokens: number; enc: number; dec: number; got: string[] };
const rows = new Map<string, Row>(); let totOk = 0, tot = 0;
for (const f of readdirSync("long_tensors").sort()) {
  const d = JSON.parse(readFileSync("long_tensors/" + f, "utf8")); const [base, size] = f.replace(".json", "").split("@");
  const u8 = Buffer.from(d.tensor, "base64"); const tensor = new Float32Array(u8.length); for (let i = 0; i < u8.length; i++) tensor[i] = u8[i]! / 255;
  const r = await eng.recognize({ tensor, height: d.h, width: d.w, mask: new Uint8Array(Buffer.from(d.mask, "base64")), maskHeight: d.h, maskWidth: d.w } as any, vocab, "auto");
  const ok = squash(collapse(r.latex)) === squash(d.label); tot++; totOk += ok ? 1 : 0;
  const key = base!.replace(/_w\d$/, ""); const row = rows.get(key) ?? { name: key, ok: 0, n: 0, w: 0, tokens: 0, enc: 0, dec: 0, got: [] };
  row.n++; row.ok += ok ? 1 : 0; row.w = Math.max(row.w, d.w); row.tokens = Math.max(row.tokens, r.tokenIds.length); row.enc += r.encoderMs; row.dec += r.decoderMs;
  if (!ok && row.got.length < 2) row.got.push(`${squash(r.latex)} (size ${size})`); rows.set(key, row);
}
console.log("expression".padEnd(24), "ok".padStart(7), "width".padStart(6), "tokens".padStart(7), "enc ms".padStart(7), "dec ms".padStart(7), " first misreads");
for (const r of rows.values()) console.log(r.name.padEnd(24), `${r.ok}/${r.n}`.padStart(7), String(r.w).padStart(6), String(r.tokens).padStart(7), (r.enc / r.n).toFixed(0).padStart(7), (r.dec / r.n).toFixed(0).padStart(7), " ", r.got.join(" | "));
console.log(`\nTOTAL ${totOk}/${tot} (${(100 * totOk / tot).toFixed(1)}%)`);
