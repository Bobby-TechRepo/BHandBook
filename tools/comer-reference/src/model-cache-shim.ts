import { readFileSync } from "fs";
export async function fetchWithCache(url: string): Promise<ArrayBuffer> {
  const b = readFileSync(url);
  return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;
}
