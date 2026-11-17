// Node stand-in for "onnxruntime-web": same Tensor / InferenceSession API, native CPU backend.
import * as ortNode from "onnxruntime-node";
export const Tensor = ortNode.Tensor;
export const InferenceSession = ortNode.InferenceSession;
export const env: any = { wasm: {} };
