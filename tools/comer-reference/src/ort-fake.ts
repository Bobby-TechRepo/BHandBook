// Deterministic fake ONNX runtime so the UNMODIFIED reference decoding logic (beam search, masking, repeat limit,
// candidate selection) can be exercised with scripted logits and compared against the Kotlin port.
const V = 113;
export class Tensor {
  constructor(public type: string, public data: any, public dims: number[]) {}
  dispose() {}
}
export const env: any = { wasm: {} };
export function fakeLogits(ids: number[], variant: number): Float32Array {
  let h = 17;
  for (const id of ids) h = (Math.imul(h, 31) + id) | 0;
  const row = new Float32Array(V);
  for (let i = 0; i < V; i++) {
    const x = (Math.imul(h, i + 13) + i * i * 7) >>> 0;
    row[i] = (x % 997) / 50 - 8;
  }
  const last = ids[ids.length - 1]!;
  const mode = variant % 3;
  if (ids.length > 5 + (variant % 7)) row[2] += 14;                      // make <eos> reachable at varying lengths
  if (mode === 1) row[last] += 6;                                        // sticky token -> exercises repeat limit
  if (mode === 2) {                                                      // structure-like: \frac { d } { d }
    if (last === 53) row[110] += 10;
    else if (last === 110) row[11 + (Math.abs(h) % 10)] += 9;
    else if (last >= 11 && last <= 20 && ids.length > 4) row[112] += 8;
    else if (last === 112) row[110] += 5;
  }
  const out = new Float32Array(ids.length * V);                          // reference reads only the last row
  out.set(row, (ids.length - 1) * V);
  return out;
}
export const InferenceSession = {
  async create(buf: ArrayBuffer) {
    const kind = new Uint8Array(buf)[0] === 69 ? "enc" : "dec";
    return {
      async run(feeds: any) {
        if (kind === "enc") return { encoder_features: new Tensor("float32", new Float32Array(1), [1]), encoder_mask: new Tensor("bool", new Uint8Array(1), [1]) };
        const ids = Array.from(feeds.input_ids.data as BigInt64Array, (b) => Number(b));
        return { logits: new Tensor("float32", fakeLogits(ids, (globalThis as any).__variant), [1, ids.length, V]) };
      },
      release() {},
    };
  },
};
