# Manual QA checklist (device)

Everything below was verified in the JVM test suite as far as it can be (logic, geometry, layout). The Android drawing, gestures and settings UI could
not be run where this project was written, so this list is what to check on a real device. Tick each line; note the device and build type.

## Recognition
- [ ] Write `25+15=`: result `40` appears quickly after `=`. Write `2x+5=15`: steps end in `x = 5`.
- [ ] Logcat (tag `ComerOnnx`): one "CoMER ready in … ms" line. Tag `ComerRuntime`: "warm-up inference took … ms", then one line per recognition.
- [ ] Draw again while "Recognizing…" is showing: the app must not crash; the old reading is replaced by the new one.
- [ ] Airplane mode on: recognition still works (the app has no INTERNET permission).

## Correcting misreads and first run
- [ ] On a fresh install (or after clearing app data) an empty canvas shows the welcome card; drawing a stroke dismisses it for good. "Got it" also dismisses it. Afterwards an empty canvas shows only the faint hint. Settings > Show tips opens the tips sheet.
- [ ] Write something the model tends to misread (a `7` that looks like a `1`, an `x` like a `×`). With the select tool tap it > Edit: under "Did you mean" there are up to three alternative readings; tapping one applies it immediately and closes the dialog. After applying, reopening Edit shows no stale suggestions.
- [ ] In the Edit dialog the symbol tray inserts at the cursor (try `√(` in the middle of the text) and the text field stays editable.

## Modes
- [ ] A chip under the top bar shows `DEG` on a fresh install. Write `sin(30)=` (typed names are not needed: write `sin` by hand): `1/2` with `0.5` beside it. Tap the chip, choose Radians: the answer changes to `-0.988…` immediately, and the chip reads `RAD`. Gradians: `0.4539…`.
- [ ] In degrees: `cos(90)=` is `0`, `tan(45)=` is `1`, `tan(90)=` says undefined, `sin(180)=` is `0`.
- [ ] Handwrite `sin⁻¹(1/2)` (a small `-1` as an exponent): `30` in degrees, `100` for `sin⁻¹(1)` in gradians.
- [ ] Decimal and Decimal places: `10÷4=` shows `5/2` (Auto), `2.5` (Decimal), `2.50` (2 places). A column sum follows the places too. `3x=10` shows `x = 10/3`, then `x = 3.33` with 2 places, with an extra "round the answer" step.
- [ ] Graph `y = sin(x)` in degrees: whole waves from -360 to 360. Switch to radians: the graph redraws with the +-10 window.
- [ ] Money: in the Modes panel turn on Thousands separators, choose ₦ and 2 decimal places. Write `1234.5×1000=`: `₦1,234,500.00`. The preview line in the panel agrees. A column total reads the same way. `10÷3=` stays `10/3` (no currency on a fraction). An equation such as `x=1234567` still answers `x = 1234567`, and steps show plain numbers. The chip reads e.g. `DEG · FIX 2 · ₦`.
- [ ] Close and reopen the app: the modes are remembered, and answers on an old canvas agree with the chip.

## Writing direction and lines
- [ ] Turn the device sideways (app not rotated) and write `25+15=`: it is read, and the answer is written the same way up as your writing.
- [ ] Write two equations one under the other (`x + y = 5` / `x - y = 1`): two separate expressions, `x = 3, y = 2` under the lower one, no red error.
- [ ] Write a fraction, an exponent (`x²`) and a square root: each stays one expression.

## Column sums
- [ ] `105` / `- 37` / bar: total `68` under the bar, right-aligned, at handwriting size.
- [ ] Same, but write your own answer under the bar: it is not added in; the app's total is written below it.
- [ ] Rows with decimals (`12.50`, `3.75`) and thousands commas (`1,500`).

## Graphs
- [ ] Write `y = 2x + 4`: a **Graph** button appears above the toolbar (pen tool still active). Tap it: the graph appears and the "tap Graph" hint disappears.
- [ ] Touch the graph anywhere and drag: it moves. Try with the pen tool active: no ink is drawn on or through the panel. Draw a stroke toward it: the stroke ends at the panel's edge.
- [ ] Drag the bottom-right corner: only that graph resizes (try two graphs).
- [ ] Circle `x² + y² = 25`: a round circle (equal axis scales). Circle plus `y = x` stacked: two intersections marked with dashed guides and values on the axes.
- [ ] Close and reopen the app (and switch canvases and back): graphs that were showing are still showing, at the same place and size. Hide a graph, move/resize nothing, show it again: same place and size. Delete the equation: its graph is gone.
- [ ] Settings > Graphs: thickness slider, grid, guide lines, point labels, legend, default size all take effect and survive a restart.
- [ ] Zoom out much further than before (down to 0.2 %) and pan around a large canvas: still smooth enough?

## Dark theme, fonts, answer/steps, resize
- [ ] Dark theme (the half-circle button cycles System / Light / Dark): the canvas becomes dark as well (grid kept), ink turns light, and EVERY icon in the top bar, the tool bar (pen, eraser, select, hand, undo, redo, trash) and the context bars is clearly visible; the selected tool is highlighted. Switch back: all readable on the light canvas. Shared PNG / PDF stay light whatever the theme.
- [ ] Answers and steps are in the Handlee handwriting font (not the plain font), at a readable size, in light and dark. Bigger numbers and fractions still look right.
- [ ] An expression with steps shows EITHER its answer (steps hidden) OR its steps (answer hidden); the steps' last line is the answer. Toggle "Show steps" / "Hide steps" and watch them swap. An expression without steps always shows its answer. Same for two equations solved together.
- [ ] Select an expression: a round handle appears at the bottom-right corner. Drag it right: the handwriting, the answer and the steps grow together (live), release: it stays, and nothing is re-read (the answer does not flicker). Drag left: smaller. One undo restores the original size exactly. In the multi-select mode the handle resizes the whole selection together. The handle does not appear with the pen tool.

## Multi-select and search
- [ ] Write four expressions. Select tool, tap one: its context bar has "Select more". Press it: a bar shows "1 selected" with All / Delete / Done. Tap two more: highlighted, "3 selected". Tap one of them again: it is deselected. Tapping empty space changes nothing; panning still works.
- [ ] Drag one selected expression: all selected ones move together while you drag (the unselected one stays). One undo puts them all back. "All" selects everything; "Delete" removes the selection, one undo restores it. "Done" (or choosing another tool) leaves the mode.
- [ ] Switch canvases while in the mode: it is off on the other canvas.
- [ ] Canvases sheet: type part of an expression (try `12*8` for something written `12×8`), then an answer (`96`), then part of a canvas name. Hits show the text, the answer and the canvas. Tap a hit on another canvas: it opens, the expression is selected and framed. A search for nonsense says "No matches"; clearing the box clears the list. With several large canvases, typing stays responsive.

## Inside a graph: pan, zoom, trace
- [ ] Graph `y = x^2 - 4`. With two fingers on the panel, spread apart: the graph zooms in about the point between your fingers (the curve does not jump); drag two fingers: the graph pans. The panel itself does not move, and the canvas does not zoom. One finger on the panel still moves the panel; the corner grip still resizes it.
- [ ] Zoom far in and far out: the axes get new nice numbers when you let go, the curve is drawn across the whole new range, nothing breaks at extremes. Double tap the graph: automatic framing again.
- [ ] Close and reopen the app (and back up and restore the canvas): the zoomed graph is as you left it.
- [ ] Tap near the curve: a marker with `(x, y)` appears on the curve; tap the same marker again: it goes. Tap near an intersection of two graphs or a root: it snaps there and shows its exact coordinates. Graph `x^2 + y^2 = 25` and `x = 3`: trace stays on the circle / follows the vertical line. Tap far from every curve: nothing.
- [ ] Shared PNG / PDF show no trace marker. Change the equation: the old marker is gone.
- [ ] With the app on a low-end phone, a two-finger zoom on a circle graph stays smooth.

## Show all
- [ ] On a blank canvas the "Show all" button is not shown; after the first stroke it appears under the mode chip.
- [ ] Write in three places far apart (zoom out between), move the view elsewhere, tap "Show all": everything, including answers and any graph panel, is on screen and roughly centred. Tap it with a single tiny mark: no huge zoom (at most 2x).
- [ ] The button does not block drawing near it, and its label is announced by TalkBack.

## Values and functions
- [ ] Write `a = 5`, then elsewhere `2a + 1 =`: it answers 11 and its steps start with "Use the values you defined: a = 5". Change the 5 (erase and rewrite it as 6): the other answer updates by itself to 13.
- [ ] Write `2b + 1 =` first (an error that says how to define `b`), then `b = 4` anywhere: the first answer fills in with 9.
- [ ] Write `f(x) = x^2 - 4`, then `f(3) =` (5), `2f(3) =`, and `f(3)^2 =`. Tap Graph on the definition: one curve, labelled y = f(x). Write `y = f(x)` elsewhere: it can be graphed too.
- [ ] `x + a = 10` with `a = 3` defined: `x = 7` with a first step "Use the values you defined: a = 3". `a + 2 = 9` still answers `a = 7`.
- [ ] Try to name `x` (write `x = 5`): it just answers `x = 5` and changes nothing else. Write `a = b + 1` and `b = a + 1`: the Modes panel reports a circular definition and `2a =` does not use it.
- [ ] Modes panel: "Values and functions on this canvas" lists what is defined; with a = 5, f(x) = 2x + 1 it shows both. Back up and restore a canvas with definitions: the dependent answers are the same.

## Backup and restore
- [ ] Open the Canvases sheet. Each canvas has Rename / Back up / Delete under its name (the layout stays readable on a narrow phone). "Import canvas file" sits under "New canvas".
- [ ] Back up a canvas that has ink, a solved equation, a column, a graph (dragged and resized) and non-default modes: the share sheet opens with a `.bhand` file; save it (Drive / Files).
- [ ] Import that file with the picker: a new canvas opens named like the original (with "(2)" if the name is taken) with a toast; the ink, answers, steps, graph position and size match; the original canvas is untouched. Import it again: "(3)".
- [ ] Back up the canvas you are on, right after drawing: the file includes the latest strokes.
- [ ] Import something that is not a backup (a photo, a text file): a message says so and no canvas is created. Import a backup you edited by hand to cut it short: a "damaged" message. Cancel the picker: nothing happens.
- [ ] Back up and restore a big canvas (thousands of strokes): the app stays responsive while it works.

## Sharing (image and PDF)
- [ ] Tap the share icon on an empty canvas, choose either format: "Nothing to share yet". The menu offers only "Image (PNG, transparent)" and "PDF".
- [ ] With ink, results, a system of equations and a graph (dragged away from its equation, and resized): share as PNG into a messaging app. The image shows everything, cropped with a margin, dark ink on a **transparent** background (check it on a coloured chat background), text the same size relative to the ink as on screen, no selection outline, no hint lines, no resize grip.
- [ ] Same canvas as PDF: opens in a PDF viewer, one page, white background, text sharp when zoomed (vector).
- [ ] Switch the app to dark theme and share again: the picture is unchanged (dark ink).
- [ ] Share a very large canvas (zoomed far out with lots of ink): either works or shows "too large", never crashes.
- [ ] The canvas name in the top bar shortens with an ellipsis on a narrow phone, and all icons stay visible.

## Release build (important)
- [ ] Build and install a **release** variant and repeat the recognition checks. If logcat shows "primary recogniser is not usable in this build" or
      "CoMER unavailable", the keep rules in `app/proguard-rules.pro` are not applied: wire the file into the release build type (see README, "Release builds").


## Release build (run on the signed RELEASE build, not a debug build)
- [ ] `python tools/check_release.py` reports no FAIL (ONNX Runtime class names kept, native libraries for both CPU types, font and notices present, no INTERNET permission, size within budget).
- [ ] Installed on a phone: handwriting recognition is as accurate as in the debug build (check `adb logcat | grep -i comer`: there must be no "primary recogniser is not usable"). The first recognition after install can be slow (see the README); later ones take about a second.
- [ ] Everything in the sections above still works in the release build (R8 can break things that debug does not): draw, recognise, columns, equations, graphs (pan, zoom, trace), definitions, export PNG / PDF, backup and restore, search, resize.
- [ ] Settings > About & licenses shows the version and the licence text (including the Handlee licence).
- [ ] Airplane mode: everything works the same.
- [ ] Settings > Apps > the app > Permissions: none listed.
- [ ] Update test: install the previous release build, draw something, install the new build over it (same signing key): the canvases are still there.
