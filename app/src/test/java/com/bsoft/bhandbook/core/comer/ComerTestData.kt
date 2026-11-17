package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.codec.MiniJson
import java.io.File

/** Golden data produced by running the UNMODIFIED ink-on reference (see tools/comer-reference) and, when present, the real vocabulary. */
object ComerTestData {
    fun text(name: String): String = (ComerTestData::class.java.getResourceAsStream("/comer/$name") ?: error("missing test resource comer/$name")).bufferedReader().readText()
    fun bytes(name: String): ByteArray = (ComerTestData::class.java.getResourceAsStream("/comer/$name") ?: error("missing test resource comer/$name")).readBytes()
    fun gunzip(name: String): ByteArray = java.util.zip.GZIPInputStream(bytes(name).inputStream()).readBytes()
    fun json(name: String): Any? = MiniJson.parse(text(name))

    /** The release vocab.json lives in assets only after scripts/fetch_comer_models.py has been run (the repo does not ship model files). */
    fun vocabJson(): String? {
        val candidates = listOfNotNull(System.getenv("COMER_MODELS_DIR"), System.getProperty("comer.models"), "src/main/assets/models/comer", "app/src/main/assets/models/comer")
        return candidates.map { File(it, "vocab.json") }.firstOrNull { it.exists() }?.readText()
    }
    fun vocab(): ComerVocab? = vocabJson()?.let { ComerVocab.parse(it) }

    @Suppress("UNCHECKED_CAST") fun list(v: Any?) = v as List<Any?>
    @Suppress("UNCHECKED_CAST") fun map(v: Any?) = v as Map<String, Any?>
    fun strings(v: Any?): List<String> = list(v).map { it as String }
    fun ints(v: Any?): List<Int> = list(v).map { (it as Double).toInt() }
}
