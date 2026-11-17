package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.Stroke
import java.util.Collections

/** Offline, model-free recogniser: stroke segmentation -> symbol classification ($P templates + geometry) -> 2-D layout. */
class HandwritingMathRecognizer : MathRecognitionEngine {
    override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult {
        val usable = strokes.filter { it.points.isNotEmpty() }
        if (usable.isEmpty()) return RecognitionResult(MathExpression.Empty, 0f, false, listOf("nothing to recognise"))
        val ref = Geometry.refSize(usable)
        val clusters = Segmenter.segment(usable, ref, isCancelled)
        val syms = clusters.map { SymbolClassifier.classify(it, ref, isCancelled) }
        if (isCancelled()) throw RecognitionCancelled()
        val expr = LayoutAnalyzer.analyze(syms)
        val confidence = syms.minOf { it.conf }
        val warnings = buildList {
            if (syms.any { it.label == "?" }) add("unrecognised symbol")
            if (confidence < RecognitionThresholds.RELIABLE_CONFIDENCE) add("low confidence")
        }
        return RecognitionResult(expr, confidence, warnings.isEmpty(), warnings)
    }
}

/** Cheap check used to trigger immediate recognition: is the right-most symbol an '=' sign? */
object EqualsDetector {
    fun endsWithEquals(strokes: List<Stroke>): Boolean {
        val usable = strokes.filter { it.points.isNotEmpty() }
        if (usable.size < 3) return false
        val ref = Geometry.refSize(usable)
        val clusters = Segmenter.segment(usable, ref) { false }
        val last = clusters.maxByOrNull { Geometry.union(it).right } ?: return false
        return SymbolClassifier.isEqualsCluster(last, ref)
    }
}

/** LRU cache keyed by a translation-invariant stroke signature, so moving or undoing back to identical ink never re-recognises. */
class RecognitionCache(private val capacity: Int) {
    private val map = Collections.synchronizedMap(object : LinkedHashMap<Int, RecognitionResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RecognitionResult>?) = size > capacity
    })

    fun signature(strokes: List<Stroke>): Int = InkSignature.of(strokes)
    operator fun get(sig: Int): RecognitionResult? = map[sig]
    fun put(sig: Int, r: RecognitionResult) { map[sig] = r }
    fun clear() = map.clear()
}

class CachingRecognizer(private val inner: MathRecognitionEngine, private val cache: RecognitionCache) : MathRecognitionEngine {
    override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult = recognize(strokes, RecognitionHint.ANY, isCancelled)

    /** The hint is part of the cache key: the same ink can be read differently as "a number" than as "any expression". */
    override fun recognize(strokes: List<Stroke>, hint: RecognitionHint, isCancelled: () -> Boolean): RecognitionResult {
        val sig = cache.signature(strokes) + hint.ordinal * 0x9E3779B1.toInt()
        cache[sig]?.let { return it }
        return inner.recognize(strokes, hint, isCancelled).also { cache.put(sig, it) }
    }
}

/** Translation-invariant fingerprint of ink (0.5 px resolution). Equal ink => equal signature, wherever it sits on the canvas. */
object InkSignature {
    fun of(strokes: List<Stroke>): Int {
        if (strokes.isEmpty()) return 0
        val b = strokes.fold(WorldRect.Empty) { a, s -> a.union(s.bounds) }
        var h = strokes.size
        for (s in strokes) {
            h = h * 31 + s.points.size
            for (p in s.points) { h = h * 31 + ((p.x - b.left) * 2f).toInt(); h = h * 31 + ((p.y - b.top) * 2f).toInt() }
        }
        return if (h == 0) 1 else h
    }
}
