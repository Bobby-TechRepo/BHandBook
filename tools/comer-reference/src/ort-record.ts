// Wraps the real onnxruntime-node and records every decoder query: prefix ids -> logits of the last position.
import * as ortNode from "onnxruntime-node";
export const Tensor = ortNode.Tensor;
export const env: any = { wasm: {} };
export const recorded = new Map<string, Float32Array>();
export const InferenceSession = {
  async create(buf: any, opts: any) {
    const s: any = await (ortNode.InferenceSession as any).create(buf, opts);
    if (!s.inputNames.includes("input_ids")) return s;
    return {
      inputNames: s.inputNames, outputNames: s.outputNames,
      async run(feeds: any) {
        const res = await s.run(feeds);
        const ids = Array.from(feeds.input_ids.data as BigInt64Array, (b) => Number(b));
        const logits = res.logits.data as Float32Array, V = 113;
        recorded.set(ids.join(","), Float32Array.from(logits.subarray((ids.length - 1) * V, ids.length * V)));
        return res;
      },
      release: () => s.release(),
    };
  },
};
