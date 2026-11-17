package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke

/**
 * Uses the [primary] engine (CoMER) when it is available and falls back to the built-in geometric recogniser when it is missing or fails,
 * so a missing model file or a native failure degrades recognition instead of breaking drawing. Cancellation is never swallowed.
 */
class FallbackRecognitionEngine(
    private val primary: () -> MathRecognitionEngine?,
    private val fallback: MathRecognitionEngine,
    private val onFallback: (String) -> Unit = {},
) : MathRecognitionEngine {

    override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult = recognize(strokes, RecognitionHint.ANY, isCancelled)

    override fun recognize(strokes: List<Stroke>, hint: RecognitionHint, isCancelled: () -> Boolean): RecognitionResult {
        val engine = primary()
        if (engine != null) {
            try {
                return engine.recognize(strokes, hint, isCancelled)
            } catch (c: RecognitionCancelled) {
                throw c
            } catch (e: Exception) {
                onFallback("primary recogniser failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: OutOfMemoryError) {
                onFallback("primary recogniser ran out of memory")
            } catch (e: LinkageError) {
                // missing/renamed classes or native libraries (e.g. a release build that shrank ONNX Runtime): degrade, do not crash
                onFallback("primary recogniser is not usable in this build: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else onFallback("primary recogniser unavailable")
        val r = fallback.recognize(strokes, hint, isCancelled)
        return r.copy(warnings = r.warnings + NOTICE)
    }

    companion object { const val NOTICE = "Using the built-in recognizer (the CoMER model is unavailable)" }
}
