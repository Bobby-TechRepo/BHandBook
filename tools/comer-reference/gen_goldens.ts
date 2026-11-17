import { readFileSync, writeFileSync } from "fs";
const { repairLatex, isCompleteExpression } = await import("./src/latex-repair.ts");
const { InferenceEngine } = await import("./src/inference_fake.ts");
const { isStructurallyValid, applyModeMask } = await import("./src/inference_fake.ts");
const { decodeToTokenArray } = await import("./src/tokenizer.ts");
const { resamplePoints, isStrokeMeaningful } = await import("./src/preprocessing_browser.ts");
const vocab = JSON.parse(readFileSync(((process.env.COMER_MODELS_DIR ?? "../../app/src/main/assets/models/comer").replace(/\/?$/, "/")) + "vocab.json", "utf8"));

// ---------- 1. repair / completeness / structural validity over a corpus
let seed = 12345; const rnd = () => ((seed = (Math.imul(seed, 1103515245) + 12345) & 0x7fffffff) / 0x7fffffff);
const alphabet = ["\\frac", "\\sqrt", "{", "}", "{", "}", "2", "5", "+", "x", "^", "=", "\\sin", "(", ")", "\\int", "\\times", "-", "_"];
const fixed = [[], ["\\frac"], ["\\sqrt"], ["\\frac", "{", "1", "}"], ["\\frac", "{", "1", "}", "{", "2", "}"], ["\\frac", "1", "2"], ["\\frac", "{", "1", "}", "2"],
  ["\\sqrt", "{", "9"], ["\\sqrt", "9"], ["}", "}", "a"], ["{", "{", "a"], ["\\frac", "{", "1", "}", "{", "2", "}", "{", "3", "}"], ["\\sqrt", "{", "1", "}", "{", "2", "}"],
  ["\\frac", "{", "\\frac", "{", "1", "}", "{", "2", "}", "}", "{", "3", "}"], ["\\sin"], ["\\sum"], ["2", "^", "{", "2"], ["2", "^"], ["x", "+"], ["+"], ["\\frac", "}", "{", "a", "}"],
  ["\\frac", "{", "a", "}", "}", "{", "b", "}"], ["\\sqrt", "\\sqrt", "{", "2", "}"], ["\\frac", "\\frac", "1", "2", "3"]];
const corpus: string[][] = [...fixed];
for (let n = 0; n < 700; n++) { const len = 1 + Math.floor(rnd() * 12); corpus.push(Array.from({ length: len }, () => alphabet[Math.floor(rnd() * alphabet.length)]!)); }
const latexCases = corpus.map((t) => { const rep = repairLatex(t); return { tokens: t, repaired: rep, completeBefore: isCompleteExpression(t), completeAfter: isCompleteExpression(rep), validBefore: isStructurallyValid(t.join(" ")), validAfter: isStructurallyValid(rep.join(" ")) }; });
writeFileSync("golden/latex_cases.json", JSON.stringify(latexCases));
console.log("latex cases:", latexCases.length, "| incomplete before:", latexCases.filter((c) => !c.completeBefore).length, "| changed by repair:", latexCases.filter((c) => c.tokens.join() !== c.repaired.join()).length);

// ---------- 2. decoding (beam/greedy/modes) through the unmodified reference engine with scripted logits
const decodeCases: any[] = [];
const M = "enc";
for (let variant = 0; variant < 45; variant++) for (const beam of [1, 2, 3]) for (const mode of ["auto", "number", "expression"]) {
  (globalThis as any).__variant = variant;
  const eng = new InferenceEngine({ encoderUrl: "enc", decoderUrl: "dec", beamWidth: beam, executionProvider: "cpu" as any });
  const r = await eng.recognize({ tensor: new Float32Array(1), height: 1, width: 1, mask: new Uint8Array(1), maskHeight: 1, maskWidth: 1 } as any, vocab, mode as any);
  decodeCases.push({ variant, beam, mode, latex: r.latex, tokenIds: r.tokenIds });
  eng.dispose();
}
writeFileSync("golden/decode_cases.json", JSON.stringify(decodeCases));
console.log("decode cases:", decodeCases.length, "| non-empty latex:", decodeCases.filter((c) => c.latex).length, "| avg tokens:", (decodeCases.reduce((s, c) => s + c.tokenIds.length, 0) / decodeCases.length).toFixed(1));

// ---------- 3. resampling + meaningfulness
const lineA = [{ x: 0, y: 0 }, { x: 30, y: 0 }], curve = Array.from({ length: 40 }, (_, i) => ({ x: i * 2.3, y: 20 * Math.sin(i / 5) })), diag = [{ x: 0, y: 0 }, { x: 17, y: 31 }, { x: 40, y: 12 }], uneven = [{ x: 0, y: 0 }, { x: 0.5, y: 0 }, { x: 40, y: 3 }, { x: 41, y: 3 }, { x: 90, y: 50 }];
const rs = { single: [{ x: 5, y: 5 }], empty: [], line10: [lineA, 10], line3: [lineA, 3], curve3: [curve, 3], diag3: [diag, 3], uneven3: [uneven, 3], short: [[{ x: 0, y: 0 }, { x: 1, y: 0 }], 3] } as any;
const resampleCases = Object.entries(rs).map(([name, v]: any) => { const pts = name === "single" || name === "empty" ? v : v[0]; const iv = name === "single" || name === "empty" ? 3 : v[1]; return { name, input: pts, interval: iv, output: resamplePoints(pts, iv) }; });
writeFileSync("golden/resample_cases.json", JSON.stringify(resampleCases));
const mk = (n: number, dx: number, dy = 0, lw = 3) => ({ points: Array.from({ length: n }, (_, i) => ({ x: i * dx, y: i * dy })), lineWidth: lw });
const meaningCases = [["empty", []], ["tiny dot", [{ points: [{ x: 10, y: 10 }, { x: 11, y: 10 }], lineWidth: 3 }]], ["two points far", [{ points: [{ x: 0, y: 0 }, { x: 50, y: 50 }], lineWidth: 3 }]],
  ["long line", [mk(21, 5)]], ["short path 5pts", [mk(5, 2)]], ["small bbox many pts", [mk(30, 0.2)]], ["two small strokes", [{ points: Array.from({ length: 5 }, (_, i) => ({ x: i * 7.5, y: 50 }) ), lineWidth: 3 }, { points: Array.from({ length: 5 }, (_, i) => ({ x: 40 + i * 7.5, y: 50 }) ), lineWidth: 3 }]],
  ["exactly 8x0", [mk(6, 1.6)]], ["8x8 box 6 pts", [{ points: [{x:0,y:0},{x:8,y:0},{x:8,y:8},{x:0,y:8},{x:0,y:0},{x:4,y:4}], lineWidth: 3 }]]].map(([name, strokes]: any) => ({ name, strokes, meaningful: isStrokeMeaningful(strokes) }));
writeFileSync("golden/meaningful_cases.json", JSON.stringify(meaningCases));
console.log("meaningful:", meaningCases.map((c) => `${c.name}=${c.meaningful}`).join(" | "));
