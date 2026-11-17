# Infinite Canvas Calculator

Android app (Kotlin, Jetpack Compose) where handwriting on an infinite canvas is the only interface: write `25 + 15 =`
and the answer appears next to it; write `2x + 5 = 15` and the step-by-step solution appears underneath.
Built on the supplied `BHandBook` Compose template (package `com.bsoft.bhandbook`).

## Read this first: what is and is not verified

The build environment used to write this had a JDK and the Kotlin compiler but **no Android SDK, no Google Maven and no
emulator**. So the project was split by what could honestly be checked:

| Part | Status |
|---|---|
| Pure-Kotlin core: canvas transforms, strokes/eraser, undo-redo, canvas editor, grouping, **recognizer**, parser, exact evaluator, step solver, codecs, use case, layout, perf tests | **1072 JUnit tests written test-first and run: all pass** (the ViewModel tests, which need Android libraries, bring the total to 451+; all of them ran green on a developer machine apart from three test mistakes since fixed) |
| ViewModel, Compose screen/gestures, Room, Hilt, Gradle wiring | Compiles and runs on a device (after one fix to `CanvasSurface`); see device results below |
| `RoomPersistenceTest` (10), `CanvasScreenTest` (17) | **Run on a physical device: 26 of 27 passed**; the 2 failures were test bugs (wrong `pinch` argument order; tablet panel asserted visible on a phone-sized window), since fixed and awaiting re-run |
| `CanvasViewModelTest` (20) | Written, **never run** |

Expect first-build friction in the unverified layer (typos in imports, a Compose/Room API nuance). The logic those
layers delegate to is the tested core. Dependency versions in `gradle/libs.versions.toml` (Hilt 2.59.2, KSP 2.3.6,
Room 2.8.4) were chosen from release notes for AGP 9's built-in Kotlin but could not be resolved offline; bump if Gradle complains.

Run on a machine with the Android SDK: `./gradlew test` (JVM tests incl. ViewModel) and `./gradlew connectedAndroidTest`.

## Architecture

Single Gradle module, layered by package (the spec's multi-module layout was deliberately not used: module wiring is
exactly the kind of thing that cannot be validated without a build; the package layering keeps the same dependency
direction and can be split later mechanically).

```
core/graphics     CanvasTransform (screen<->world, zoom about focal point, fit, grid spacing), Vec2, WorldRect
core/math         Rational, AST, DefaultMathParser (recursive descent), ExactCalculationEngine, NumberFormatter, Printer
core/recognition  HandwritingMathRecognizer (segmenter, $P classifier, layout analyzer), cache, grouping, equals detection
core/codec        compact stroke blob, solution-step and canvas codecs
domain/model      Stroke, ExpressionObject, CanvasState, settings
domain/history    command-based undo/redo (deltas only, never canvas copies)
domain/editor     CanvasEditor (all editing rules), ExpressionLayout (where results/steps are drawn)
domain/solver     ArithmeticStepper, Poly, EquationSolver (linear + quadratic), SolutionStep
domain/usecase    ProcessExpressionUseCase: ink -> recognise -> parse -> evaluate/solve; OutcomeMapper
domain/repository CanvasRepository, StrokeRepository, ExpressionRepository
data/             Room entities/DAOs, incremental-sync repositories, SettingsStore
feature/          canvas (ViewModel, surface, renderer, screen), workspace (canvas list), settings
di/ app/          Hilt modules, Application, MainActivity
```
Everything under `core/` and `domain/` is plain Kotlin (no Android imports), which is what made it testable here.
Recognition, parsing, evaluation, solving and rendering are independent and meet only in `ProcessExpressionUseCase`.

## Measuring recognition on your own handwriting

The synthetic accuracy numbers below say little about real writing, so the app can collect real samples (opt-in, on-device only):
select an expression → **Edit** → type what you meant → tick *Save this handwriting as a sample* → **Apply**. Then
`adb pull /sdcard/Android/data/com.bsoft.bhandbook/files/samples/ app/src/test/resources/` and run `./gradlew test`:
`RealSamplesTest` prints accuracy, every misread, and the *silent error rate* (wrong answers shown without asking), and fails above 10 %.
Send the misreads back to retune templates and thresholds against real data.

## Icons

Toolbar and action icons come from the **Glyphs** icon set (MIT, Goran Spasojevic; see `THIRD_PARTY_LICENSES.md`). They are generated, not hand-copied:
`scripts/generate_glyphs.py` reads the `@iconify-json/glyphs` npm package and writes `core/icons/GlyphData.kt` (refusing anything it cannot convert faithfully);
a unit test validates every path's syntax; `GlyphIcon` turns them into Compose vectors tinted by the theme. Redo is the undo icon mirrored.
The set has no eraser, so the eraser uses `strikethrough` (its accessibility label still says "Eraser"); change `ICONS` in the script and re-run to swap any icon.

## CoMER recognition (offline neural recogniser)

CoMER (the ink-on INT8 encoder/decoder, vocabulary of 113 tokens) is the primary recogniser; the geometric recogniser described below is the fallback
if the model files are missing or fail. Everything runs on-device through ONNX Runtime Android (`onnxruntime-android:1.22.0`, CPU); nothing is downloaded and
the app has no INTERNET permission (enforced by a test).

**The model files are not in this project.** Their redistribution rights could not be established (the upstream CoMER repository has no licence file and ships
CROHME data; ink-on has no model card). Read `THIRD_PARTY_COMER.md`, then run `python scripts/fetch_comer_models.py` (checksum-pinned, release `v0.1.0`,
commit `aaf79ca…`) before building. Without the files the app still works, using the fallback recogniser.

Pipeline (`core/comer`, plain Kotlin; ONNX only in `data/recognition/comer`): adapter (expression-local coordinates, size normalised to 80 px, pen-width-relative
validity check) → port of ink-on's preprocessing (3 px resampling, anti-aliased rasteriser, height 128 → 256×64k canvas, mask) → encoder → autoregressive decoder
(greedy/beam, number/auto/expression masks derived from token *strings*, repeat limit, 50 steps) → repair + completeness + structure checks → structured LaTeX→math
conversion → the app's existing parser/evaluator/solver. Candidates, decoder scores and timings are kept; scores are not calibrated probabilities.

How fidelity was checked (against the *unmodified* TypeScript running the real models on Node): 724 repair/completeness cases and 405 decode scenarios match exactly;
the Kotlin decoder reproduces the reference on real recorded logits (24 decodes); the Kotlin rasteriser matches the reference render (mean pixel difference 0.0004,
ink overlap 0.99, identical dimensions and masks); Kotlin-preprocessed inputs through the real models read 30/30 expressions correctly at every writing size (0.4×–5×).

Findings worth knowing:
* The reference is not size-invariant (constant 16 px pad): the same expression written larger was read worse (24/30 at 2.5×, 20/30 at 5×). The adapter therefore normalises size; 80 px is
  the middle of a 48–80 px plateau (96 px made a real error). Tuned on synthetic ink; re-tune with real samples (`RealSamplesTest`).
* The model reads a two-stroke `=` as `= =`; adjacent equals signs are merged (documented, not in the reference).
* ink-on's own benchmark reports 36.4 % exact match on CROHME 2014 (CoMER paper: 59.3 %) and that 12.7 % of that test set uses symbols outside the vocabulary.
  Simple arithmetic does far better (30/30 on my fixtures) but those fixtures are synthetic, so real accuracy is unmeasured. Not in the vocabulary: `%`, `\ln`, `\left`/`\right`.
* Unsupported notation (sums, integrals, subscripts, `\pm`, inequalities…) is shown but never calculated.

**Stress study of long and complex expressions** (14 expressions × 3 writers × 4 sizes = 168 inputs through the real models; synthetic ink): overall 64.9 % before, **71.4 % after** the two rules below, mean latency 773 → 479 ms.
* *Adaptive beam:* decoder time grows with output length (the exported graph has no key/value cache: 17 tokens ≈ 0.6 s, 36 tokens ≈ 2.1 s on the host), while greedy was as accurate as beam search here (108 vs 109 of 168) and ~3× cheaper. Inputs ≥ 768 px wide are decoded greedily (`longInputWidth`).
* *Spurious symbol after a trailing `=`:* the model sometimes echoes a digit after the equals sign (`… = 1`). When the ink's right-most symbol is an equals sign nothing can legitimately follow it, so the tail is dropped and reported (`adjustments`). No symbol is re-interpreted.
* *Very long lines:* a ~36-token line is squeezed by the 1024 px cap and digits get dropped (0/12), probably with high confidence. Content shrunk below 0.65 of the trained height is never trusted automatically (`minContentScale`). One failing point only: tune it on real data.
* *Not pipeline defects:* three of the remaining failures were my test generator (no gap after an exponent or radical so the next symbol looked part of it; a "decimal point" drawn like a comma). With spacing fixed `2^{10}+3^{2}` goes 0/12 → 12/12.

Host benchmark (x86, 1 core, ONNX Runtime Node; NOT a phone): model load + first run 0.4–1.1 s; recognition ≈ 133 ms (beam 1), 156 ms (2), 169 ms (3) = encoder ~105 ms + decoder 26–68 ms;
process memory ≈ 250 MB, flat over 400 runs once every input width had been seen. Measure your device with `ComerOnDeviceTest.benchmark_timingsPerBeamWidth` (logcat tag `ComerBenchmark`).
`tools/comer-reference` reproduces all goldens and experiments.

## Troubleshooting CoMER on a device

* **Logcat tags:** `ComerOnnx` (model load: interface, threads, load time), `ComerRuntime` (one line per recognition with preprocess / encoder / decoder milliseconds, plus a warning above 3 s), `ComerTest` / `ComerBenchmark` (instrumented tests).
* **Run `ComerOnDeviceTest.deviceSpeed_reportsHowLongOneRecognitionTakes` first.** It prints the device, ABI, core count and four timed recognitions. A phone should be about 0.2–2 s per recognition. If it is tens of seconds or minutes, the target is the problem, not the code: the usual cause is an emulator without hardware acceleration or with a CPU image that does not match the host (use x86_64 on an x86 host, arm64 on Apple silicon, with acceleration on). The INT8 kernels fall back to very slow reference code there.
* **First-call time:** one on-device run showed a very long first encoder call (60 s) against ~0.5 s afterwards. The project owner has said the reason for that run is known and unrelated to the recogniser, so no code change is made on its account. Steady state measured on that phone: preprocess 56–148 ms, encoder ≈ 550 ms, decoder ≈ 380–580 ms, i.e. about 1.1 s per recognition at beam 3. The runtime still runs one throw-away warm-up inference in the background at start-up (logged as "warm-up inference took … ms") and the on-device suite shares one session; `firstRunProbe_comparesSessionSettings` is an optional diagnostic and can be ignored.
* All on-device tests have a time limit, and the heavy ones reduce their repetition count automatically on a slow target (and log `SLOW TARGET`), so they fail or finish with a message instead of spinning.
* **Fixed bug:** drawing while a recognition was running crashed the app (`RecognitionCancelled` was an ordinary exception and escaped the coroutine). It is now a real `CancellationException`, and the recognition job also converts *any* other recogniser failure into an error shown on that expression.

## Writing orientation, column sums, simultaneous equations and graphs

**Answers are written at the size of your handwriting** (median digit height of the ink, aligned to its baseline), not scaled from the whole expression.

**Rotated writing** (device turned, app not): the writing direction is estimated from the ORDER the symbols were drawn, which also tells left-to-right from right-to-left/upside-down. Ink within 20 degrees of horizontal is read once, as before; steeply written ink is read in up to four orientations (estimate, estimate -90 degrees for stacked fractions, as written, estimate +90) and the first calculable, plausible (digits, operators, x/y/z; not stray letters), confident reading wins. Measured with the real model on synthetic ink: reading rotated ink upright 10/90 (7 silently wrong calculable answers); this search 92/100 at 1.09 model runs on average. Limits: needs 3 or more strokes (a lone `2^2` cannot be oriented); the answer text is not rotated.

**Grocery-style column sums**: two or more rows of numbers above a long bar. Detected from the ink alone (relative to handwriting size), before CoMER; each row is read separately in the model's number mode (62/69 rows exact in plain mode, 68/69 in number mode on synthetic rows; the miss is a generator artefact). Rows may start with `+ - x /` (applied top to bottom like a calculator tape: `2, +3, x4` = 20), may contain thousands commas and decimals, and arithmetic is exact. The total is written under the bar, right-aligned; drawing the bar is the "=". If you write your own answer under the bar (one row), it is not part of the sum and the app's total appears below it. A bar over one row, or with a denominator-like row and only one row above, stays a fraction. Not supported: currency symbols (not in the model's vocabulary). A lone number no longer gets a redundant answer printed under it.

**Simultaneous equations**: equations written one under the other (left-aligned, spaced like lines of text, at least one with two or more unknowns) are solved together, up to 4 unknowns, with narrated Gauss-Jordan steps, exact fractions, and the unique / no-solution / infinitely-many outcomes. The answer is written under the lowest equation. Non-linear systems are reported as unsupported.

**Lines of writing stay separate.** Two lines written close together used to merge into one expression and be read as garbage (e.g. `x_x-y=5`), so simultaneous equations never got two equations. A stroke that is a whole line away (0.9 glyph heights) from every row of an expression that already has a full line, and that is not part of a bar structure, now starts a new expression (`core/recognition/TextLines`). Fractions, exponents, roots and columns are unaffected (any long flat stroke disables splitting, and bars always join). Sideways writing is never split. Canvases saved earlier are repaired when they load or when an expression is next read (undoable). Known quirk: a wide gap along the vertical axis in sideways writing can briefly start a separate expression that merges back (older proximity rule).

**Answers follow the way you wrote.** When the ink was written turned (device rotated, app not), the answer, steps and system answer are laid out for upright ink and then turned back onto the canvas, so they read the same way as your writing. The direction is derived from the ink alone (the order the symbols were drawn, snapped to 15 degrees) so nothing extra is stored; it is deliberately conservative (enough strokes, a line-like layout, drawing order that clearly follows the line, no bar) because turning ordinary text by mistake is worse than not turning rotated text. Fractions and columns are never turned.

**Graphs are offered automatically.** When an expression you just wrote can be graphed, a Graph button appears above the toolbar (no need to switch to the select tool); a graphable equation in x and y shows "Two unknowns: tap Graph to plot it" instead of a red error. Selecting an expression also offers Graph/Hide graph in its context bar.

**Graphs**: when an expression you just wrote can be graphed a Graph button appears (or select it and use Graph in its context bar). Supported: `y = f(x)` in either order, linear equations in x and y (including vertical lines), expressions in x, one-unknown equations (both sides drawn, roots marked), systems (intersections marked), and **non-linear equations in x and y as implicit curves** (circles, ellipses, hyperbolas, sideways parabolas, `y^2 = 4` ...), traced with marching squares and stitched into continuous curves. Implicit curves are framed automatically (a scan finds the curve, so `x^2+y^2=250000` is not invisible in a +-10 window), drawn with equal axis scales so a circle is round, and marked where they cross a line, a vertical line or a curve y = f(x). An equation with no real points says so. For a non-linear system whose curves cross, the answer block reports the intersections as approximate solutions (linear systems are still solved exactly). Curves break at asymptotes and where a function is undefined. Not yet: intersections between two implicit curves. Graph panels are solid: **touch a graph anywhere and drag to move it (any tool)**, nothing can be drawn on or through one (a stroke that reaches a panel ends at its edge), and a graph moves with its expression; **drag the corner grip (bottom right) to enlarge or shrink one graph independently** (aspect ratio is kept; 0.4x to 6x; everything inside scales with it). Each intersection gets **dashed guide lines to both axes with its values printed on them** (on the nearest edge if an axis is off-screen; a point on an axis gets no guides but its value is still printed). **Settings > Graphs** controls the line thickness (the old fixed width was too heavy; 1.0x is now a thinner baseline), grid lines, guide lines, point labels, the legend and the default size of new graphs; whether a graph is showing, where it was dragged and how large it is **are remembered with the canvas** (a small JSON record per canvas in SharedPreferences, `GraphPanelCodec`: no database schema change, so nothing to migrate; damaged or unknown data is ignored, and a graph keeps its place while its equation is being re-read). Graph data is cached and only rebuilt when the equations change.

**Zoom limits:** 0.2% to 4000% (`CanvasTransform.MIN_SCALE` / `MAX_SCALE`). The minimum is a guard, not a numerical limit: below about 2% normal handwriting is under 2 px tall and every visible stroke is redrawn each frame. It was 2% and was lowered to 0.2% for a ~500x overview.

**Verification status:** all the maths, geometry and layout above is JVM-tested (607 tests). The Android drawing code (graph panel, right-aligned totals, context-bar button) and ViewModel wiring were written without being able to compile or run Android here: build and try them before relying on them. Real handwriting: the column layout of a photographed example (105 - 37 = 68) is a regression test; reading that photo with the model was inconclusive (marker blots, crude conversion), so accuracy on your own touchscreen ink remains the evidence that counts.

## Correcting misreads and first run

* **"Did you mean" (one tap):** the model already ranks alternative readings; they are now kept (as calculator text, best first, at most three; unusable, uncalculable and duplicate readings and ones that differ only by the answer-requesting `=` are dropped: `ComerAlternatives`). In the Edit dialog they appear as chips: tapping one applies it at once. They are kept in memory for the current session (recomputed whenever the ink is read again), not saved with the canvas. The dialog also has a tray of symbols that are awkward to type (`× ÷ ^ √( π ( ) | sin( cos( tan( asin( log( ln( =`) that insert at the cursor.
* **First run:** a welcome card on the first empty canvas lists the essentials (calculate, columns, equations and graphs, fixing a misread, modes, moving around); it disappears when you start writing or press "Got it". Afterwards an empty canvas shows a quiet hint. Settings > "Show tips" reopens the tips as a sheet.

## Modes (angle unit and result format)

The chip under the top bar always shows the current modes (`DEG`, `RAD`, `GRAD`, plus `DEC` / `FIX n`); tap it for the Modes panel, which also previews what the modes do (`sin(30)`, `sin⁻¹(1/2)`, `10 ÷ 4`).

* **Angle unit: Degrees (default), Radians, Gradians.** `sin`, `cos`, `tan` read their input in it; `asin`, `acos`, `atan` (typed, or handwritten `sin⁻¹`/`arcsin`) answer in it; graphs sample in it and show whole waves (±360°, ±400 grad, ±10 rad). In degrees/gradians the exact special angles are exact: `sin(30)` = `1/2`, `cos(90)` = `0`, `tan(45)` = `1`; `tan(90)` is reported as undefined (not 1.6×10¹⁶); floating-point noise such as `sin(π)` = 1.2×10⁻¹⁶ is shown as `0`. Trig constants also work inside equations and systems (`2·sin(30)·x = 5`).
* **Results: Auto** (exact fractions with the decimal beside them) or **Decimal**. **Decimal places: Auto or a fixed 0 to 9** (rounded half-up, trailing zeros kept: `5` is `5.00` with two places). The format applies to answers, arithmetic steps, column totals and the final answer of equations and systems (one extra "write the answer as a decimal" step is added; the algebra above it stays exact).
* **Money:** optional thousands separators (`1,234,567`) and a currency symbol (₦, $, €, £, GHS, KES; one to three characters) in front of displayed answers and column totals: with two decimal places a total reads `₦1,234,500.00` (`MoneyFormat`). Only plain numbers are decorated: fractions (`10/3`), scientific notation and words are left alone, and the decoration is applied to the displayed answer only, never to values that are parsed again (an equation's solutions) or to the steps. A `%` key is deliberately not offered: the handwriting model's vocabulary has no `%`, so it could only ever be typed.
* Changing a mode re-evaluates everything on the canvas from its recognised text without re-reading ink, and the modes are saved. A canvas is re-evaluated when it opens, so saved answers always match the modes in force. The model's vocabulary has no degree sign, which is why the unit is a mode, not a `°` mark.

## Dark mode, handwriting font, answers vs steps, resize

* **Dark theme darkens the canvas too** (a canvas can still choose a dark background by itself); the toolbar and context panels now set an explicit content colour, because Compose only derives one for exact theme colours and the translucent panel colour fell back to black icons on a dark panel. Exports stay light.
* **Handlee** (SIL OFL; licence in `assets/licenses` and in Settings > About & licenses) is used for answers and steps (`TextCache.layout(..., handwritten = true)`); labels and graph text keep the plain font.
* **The answer and the steps are never both shown.** While steps are visible the answer line is left out (its last step is the answer) and the steps start where it would have been; with steps hidden, or none, the answer is shown. Systems likewise.
* **Resize.** Selecting expressions (one or several) shows a round handle at the bottom-right of the selection (`SelectionBox`); dragging it scales the ink about its top-left, live, and answers and steps follow because they are laid out from the ink. One undo step (`ScaleExpressions` keeps the original strokes, so undo is exact; the ink signature is refreshed so a resized expression is not read again).

## Releasing

See `docs/RELEASE.md` (signing, versions, the model-licence blocker, build commands, Play Console steps), `docs/PRIVACY.md`, `docs/STORE_LISTING.md`, `CHANGELOG.md` and `tools/check_release.py` (verifies a release APK: ONNX Runtime names survived R8, native libraries, font, no INTERNET permission, size). Backup rules include only the canvases and settings, never the opt-in handwriting samples.

## Multi-select and search

* **Several at once.** With the select tool, tap an expression and press "Select more": a mode where each tap adds or removes an expression (including a graph's expression) and a bar shows the count with All / Delete / Done. Dragging any selected expression moves the whole selection, live; deleting removes them all. Moving or deleting several is ONE undo step (`CanvasEditor.moveExpressions` / `deleteExpressions`, composite commands), unknown ids and no-op moves are ignored. Selection state is a small pure machine (`MultiSelection`: start, tap, selectAll, prune, stop); switching tool or canvas leaves the mode, and deleted expressions drop out of the selection.
* **Search across canvases.** The Canvases sheet has a search box (`CanvasSearch`): it matches canvas names, an expression's text (as recognised or as displayed) and its answer, ignoring case and spaces and treating `x`-style operator variants as equal (`12 × 8` finds `12*8`, `÷` = `/`, the minus signs). Results are in reading order (top to bottom, then left to right), the canvas name first, at most 20 per canvas and 100 in all. The canvas on screen is searched as it is now (saved or not); the others are loaded one by one off the main thread, with a 250 ms pause while typing and cancellation when the text changes. Opening a hit opens its canvas, selects the expression and frames it in view.

## Inside a graph: pan, zoom, trace

* **Pan and zoom.** Two fingers on a graph panel move and zoom the graph itself (the graph point under your fingers stays under them); everywhere else two fingers still move and zoom the canvas, and one finger on the panel still moves the panel. A double tap on the graph returns to automatic framing. The chosen view is saved with the panel (`GraphPanelState.views`, in the panel codec and in backup files, which stay readable by older data: a missing field means automatic framing) and survives restarts. Zoom is bounded (`GraphViewport.MIN_SPAN`..`MAX_SPAN`) and bad input changes nothing. `GraphModel.layout(spec, view)` samples curves (explicit and implicit) over exactly the chosen range, with ticks for it; an invalid view falls back to the automatic framing.
* **Cheap gestures.** While fingers are down the panel only re-maps the curves it already has (instant, even for implicit curves, which sample a 120x120 grid); the curves are re-sampled once when the fingers lift, only for that graph, and the cache signature includes the view, the angle unit and the definitions in use (a changed `f(x)` redraws `y = f(x)`).
* **Trace.** A tap on a graph puts a marker on the nearest point of the curves with its coordinates; it snaps to intersections and roots when a fingertip is near one, follows vertical lines, and works on circles and other implicit curves (`GraphTrace`, on the drawn polylines; a tap far from every curve traces nothing). Tapping the marker again clears it. It is not drawn in shared images and is dropped when the graph is rebuilt from changed equations.
* `PlotArea`/`GraphPlot` hold the panel's plotting geometry in one place; the renderer draws with it and the gestures turn touches into graph coordinates with it, so drawing and touching cannot disagree.

## Show all

A "Show all" button under the top bar (shown once there is something on the canvas) frames everything written (ink, answers and graph panels) in the viewport: `CanvasView.fitAll`, built on the export area. It centres the content, never zooms in past 2x (a single small mark does not fill the screen) and never out past the transform's own limit; an empty canvas gets the default view; non-finite ink and a degenerate viewport are ignored (tested for content far from the origin, tiny and huge content, graph panels and answers).

## Values and functions

Anything written on the canvas can be reused. Write **`a = 5`** to name a value and **`f(x) = x^2 - 4`** to define a function; other expressions can then use them: `2a + 1 =` answers `11`, `f(3) =` answers `5`, `x + a = 10` is solved for `x`, and `y = f(x)` or `y = a x + b` can be graphed (a function definition on its own graphs as `y = f(x)`).

* **Rules.** The letters `x`, `y` and `z` can never be named (they are the unknowns of equations and graphs; naming them would silently change every other equation on the canvas). A value may use other values and functions, in any order on the canvas; a function may call other functions and use values. Where a name is written twice, the more recently written one counts. Something that needs a name nobody defined (`a = 2q`) is simply not a definition (it is an ordinary equation); circular definitions (`a = b + 1`, `b = a + 1`) are reported in the Modes panel and not used. Only confirmed readings define anything (not "check this" ones).
* **Equations.** With two or more names, defined ones are known and the rest are solved for; an equation in one name keeps that name as its unknown (`a + 2 = 9` is still `a = 7`, even if `a` is defined elsewhere). When nothing is left to solve the answer is "true" or "false". A value's own definition keeps showing as its answer (`b = a * 2` shows `b = 10`). The steps begin with a line saying which definitions were used.
* **Always current.** Definitions come from the recognised text (`DefinitionParser`, `DefinitionResolver`), never stored. Whenever they change (written, edited, erased, or the angle mode changes what `sin(30)` is) every expression that mentions a name is evaluated again, so no answer is ever stale; writing `2a + 1 =` *before* `a = 5` works too (it first says how to give `a` a value, then fills in). The Modes panel lists what is defined on the canvas.
* **Notes.** `f(x)^2` is understood as the square of `f(x)` (the parser reads it as `f` times `(x)^2`; the expander treats a power after a call as belonging to the call). A name directly before a bracket prints as a call, `f(x)`. Expansion is bounded, so a runaway recursion cannot hang the app (fuzz-tested: random definition sets with cycles and recursion). Handwritten names are single letters; the recogniser reads these as well as any other letters.

## Backup and restore

Canvases live only inside the app, so each one can be **backed up to a file** and **restored** from one (new phone, a copy for safekeeping, or sharing an editable canvas). In the Canvases sheet, "Back up" builds a `.bhand` file and offers it to the system share sheet (save it to Drive or Files, send it to another phone); "Import canvas file" opens the system file picker. The file is JSON with a format marker and a version (`CanvasFileCodec`): the canvas name and background, the view, every stroke (compact point arrays with time and pressure), every expression with its recognised text, answer and steps, and where its graph panel is (`bhandbook-canvas`, version 1). Properties:

* **An import always creates a NEW canvas** (named "Homework", then "Homework (2)" if taken); every stroke and expression gets a fresh id, so a file can never overwrite or merge into an existing canvas. Everything round-trips exactly (tested, including text with quotes, ₦ and √, and 500,000 points in about 2 s on a laptop; the app does the work off the main thread).
* **The reader is defensive** because the file comes from outside: it refuses non-canvas files, damaged or truncated files and files from a newer app version with a plain message; it refuses files over 30 million characters (read with a hard byte limit) or with more than 200,000 strokes, 50,000 expressions or 6,000,000 points; it drops non-finite points, clamps widths, pressures, scale and numbers, repairs a stroke that names a missing expression (it becomes plain ink), drops expressions without ink and duplicate ids, falls back to safe values for unknown statuses and backgrounds, and ignores unknown fields (forward compatibility). It never throws; the ViewModel shows the outcome as a one-time message, including how many damaged items were skipped.
* This is a manual backup, not cloud sync, and the app still has no internet permission.

## Sharing

The Share button in the top bar offers **Image (PNG, transparent)** and **PDF** (nothing else). Both contain the whole canvas: all the ink, every result, step and system answer, and every graph panel wherever it was dragged, cropped to the content with a margin (`CanvasExport`, pure and tested: area, size limits, file name). The picture is drawn by the same code as the screen (`drawStrokes` / `drawExpressionOverlays`, pointed at an offscreen bitmap or a PDF page) in fixed dark-on-light colours, so it looks the same whatever the app theme, and leaves out selection outlines, status hints and resize grips. The PNG has a transparent background (long side at most 3072 px); a PDF page cannot be transparent, so it is white (long side at most 1800 pt, vector). Files are written to `cache/shared/`, handed to the share sheet through a `FileProvider` (`res/xml/file_paths.xml`, manifest provider) and removed after a day; nothing is uploaded by the app. Not yet verified on a device: see `docs/QA-CHECKLIST.md`.

## Hardening and release builds

* **Robustness tests** (`robustness/`): awkward and random input (empty/one-point/identical/huge/tiny/non-finite ink, ~50 awkward equation strings, random systems, random edit sessions with undo/redo and line splitting) must never throw, hang or produce non-finite geometry. They found no product bugs; the failures they raised were mistakes in the tests themselves.
* **Nothing derived may crash the canvas:** insights (systems, graphs), line splitting and layout are guarded and logged; a failure shows the canvas without the extras instead of crashing. Recognition falls back to the built-in recogniser on any exception, out-of-memory or `LinkageError` (missing/renamed classes or native libraries).
* **Release builds:** `app/proguard-rules.pro` holds the keep rules ONNX Runtime needs (it calls back into Java by name from native code). The project's Gradle uses the newer `optimization { }` DSL, which could not be verified here, so the file is **not wired in automatically**: add it to the release build type using your Android Gradle plugin's syntax (classic form: `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`; the new DSL has an equivalent keep-rules option). Without the rules the app still runs but falls back to the built-in recogniser. Verify with a release build (logcat: "primary recogniser is not usable in this build" means the rules are missing).
* **Unresolved-name check for the layers the JVM tests cannot compile:** `python tools/importcheck.py app/src/main/java/com/bsoft/bhandbook` verifies that every project class/function used in `feature/`, `data/`, `di/`, `ui/` and the app files is imported, qualified or in the same package (it caught a missing import in `CanvasViewModel` once that layer was checked). It is not a compiler: Android Studio's build is the final word.
* **Manual QA:** see `docs/QA-CHECKLIST.md` for what to check on a device.

## Key decisions

**Recognition is model-free and offline.** No pretrained on-device *math* ink model could be obtained and validated here
(general OCR/handwriting engines do not understand 2-D layout). The recognizer: (1) segments strokes into symbols (touching/
crossing strokes, `=` pairs, `÷` bar-and-dots), (2) classifies with a `$P` point-cloud matcher over templates plus geometric
detectors for bars, dots, `=`, `÷` and `√`, (3) analyses layout (fractions, radicals, superscripts, decimal vs. multiplication
dot, `×` vs. variable `x`). It sits behind `MathRecognitionEngine`, so a trained model (e.g. a CROHME-trained network) can replace
it without touching anything else. **Subscripts, sums, integrals, limits, derivatives, trig/log *recognition* and the letter `y`
are not recognised yet** (the parser and evaluator already support trig/log/abs/roots/percent as typed text).

**Measured accuracy (important, and synthetic).** Test ink comes from a generator whose glyph shapes are written independently of
the recognizer's templates, with random rotation, shear, scale and jitter. Over 40 seeds × 10 expressions: **98 % exact** at normal noise,
**92 %** at 1.5× and **82.5 %** at 2×. This is *not* real handwriting; real accuracy is unknown and will be lower until the
templates are tuned on real strokes. Because of that the reliability gate is strict (confidence ≥ 0.65): wrong answers that would be
shown without asking were 0.5 % / 1.5 % / 2.75 % of expressions at those three noise levels, at the price of asking for confirmation on
~3.5 % of clean writing (threshold 0.70 removes silent errors at normal noise but asks on ~5 %). Unreliable ink is **never evaluated**; the
candidate text is shown and tapping it opens a correction dialog. Handwriting is never discarded.

*Improvement history, for honesty about what the numbers mean:* the first version was ~62 % at 2× noise. Looking at the failures showed
small glyphs collapsing (a `2` was read as `7` 73 % of the time at 0.6× size) because jitter swamps small features, so strokes are now smoothed before matching
(same filter applied to templates): `2` went from 27 % to 100 % at that size. A slash with nothing on its left is now read as a `1`.

**No Symja.** Symja is large and cannot be validated offline. A purpose-built exact solver (rational arithmetic, polynomial
algebra) covers the spec's phase-4 scope: arithmetic with precedence/parentheses/fractions/powers/roots, linear equations
(brackets, both sides, fractions, decimals, no-solution/identity) and quadratics (discriminant, exact or surd roots). Steps are
derived from the tree and exact evaluator, never by text manipulation; tests assert every step preserves the value / is satisfied by the answer.
`MathSolutionEngine` is the seam for a CAS later (differentiation, integration, systems).

**Exactness.** `0.1 + 0.2` is exactly `0.3`; `1/2 + 1/3` is `5/6`; decimals in → decimals out, integers → fractions with a decimal companion.
Very large/small values show as `a×10^n` without changing the stored value.

**Editing rules live in `CanvasEditor`, not the ViewModel.** Stroke→expression association/merging, erase-gesture-as-one-undo-step,
move/delete/clear, and "which expressions need recognition". Undoing to ink that was already recognised requests nothing; moving
never re-recognises; stale background results are rejected by an ink-signature check.

**Persistence** is incremental: only new/edited strokes are written; points are one compact blob per stroke; corrupted blobs are skipped, the rest survives.

## Spec coverage

Implemented: infinite canvas (pan, pinch, double-tap, focal zoom, unbounded), pen/eraser (partial stroke erase)/select/finger-navigation toggle,
undo/redo, clear, thickness, light/grid/dark backgrounds with zoom-aware grid, stroke grouping into expressions, recognition + feedback + correction,
parser/AST, exact evaluation, `=` triggers immediate evaluation, auto-evaluate setting + delay, results and collapsible steps drawn on canvas,
select/move/delete/recalculate/edit/zoom-to expression, multiple canvases (create/open/rename/delete), autosave, floating toolbars, themes,
tablet side panel, accessibility labels and a canvas summary.

Known gaps / honest notes: variable pen width is per-stroke from pressure (not within a stroke); no Compose *performance* instrumentation (only JVM scale tests);
the contextual-toolbar "Solve step by step" is automatic (steps are produced with every result and can be shown/hidden); 
tablet layout and gestures have not been seen on a device.

## Test-driven workflow actually followed

Each feature: tests first → confirmed red (compile errors or failing assertions) → minimal implementation → green → refactor. Notable cases where
tests caught real defects: `+` mis-read as `−1` (bar-vs-glyph rule), `+` after `x` read as an exponent (layout), redundant/incorrect solver steps
(fraction printing, literal normalisation), colliding fixture ids, and a runner mutation check proving failures are detected.
Two deviations to be upfront about: the `OutcomeMapper` red run was verified retroactively, and the Android-layer tests were written alongside, not before, the unverified UI code.
