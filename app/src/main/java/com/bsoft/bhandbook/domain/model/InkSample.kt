package com.bsoft.bhandbook.domain.model

/** One labelled piece of real handwriting: what the user meant ([label]) and what the engine read ([recognizedAs]). */
data class InkSample(val label: String, val recognizedAs: String?, val strokes: List<Stroke>, val createdAt: Long)
