package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.codec.MiniJson

/**
 * CoMER token vocabulary (vocab.json). Every id, including the special tokens, comes from the file itself;
 * nothing in the app assumes a particular ordering or size. Construction validates that the file is internally consistent.
 */
class ComerVocab private constructor(
    private val idToWord: Array<String>,
    private val wordToId: Map<String, Int>,
    val pad: Int, val sos: Int, val eos: Int,
) {
    class Invalid(message: String) : Exception(message)

    val size: Int get() = idToWord.size
    fun word(id: Int): String? = idToWord.getOrNull(id)
    fun id(word: String): Int? = wordToId[word]

    /** Token strings for [ids], skipping <pad>/<sos>/<eos> and ids outside the vocabulary (reference: decodeToTokenArray). */
    fun tokens(ids: List<Int>): List<String> {
        val out = ArrayList<String>(ids.size)
        for (id in ids) { if (id == sos || id == eos || id == pad) continue; word(id)?.let { out += it } }
        return out
    }

    companion object {
        fun parse(json: String): ComerVocab {
            val root = try { MiniJson.parse(json) as? Map<*, *> } catch (e: MiniJson.ParseFailure) { null } ?: throw Invalid("vocabulary is not a JSON object")
            val w2i = root["word2idx"] as? Map<*, *> ?: throw Invalid("missing word2idx")
            val i2w = root["idx2word"] as? Map<*, *> ?: throw Invalid("missing idx2word")
            val special = root["special_tokens"] as? Map<*, *> ?: throw Invalid("missing special_tokens")
            val declared = (root["vocab_size"] as? Double)?.toInt() ?: throw Invalid("missing vocab_size")
            if (w2i.size != declared || i2w.size != declared) throw Invalid("vocab_size $declared but word2idx has ${w2i.size} and idx2word has ${i2w.size} entries")
            val words = arrayOfNulls<String>(declared)
            for ((k, v) in i2w) {
                val id = (k as? String)?.toIntOrNull() ?: throw Invalid("non-numeric id '$k'")
                if (id !in 0 until declared) throw Invalid("id $id outside 0..${declared - 1}")
                words[id] = v as? String ?: throw Invalid("token $id is not a string")
            }
            if (words.any { it == null }) throw Invalid("ids are not contiguous")
            val map = HashMap<String, Int>()
            for ((k, v) in w2i) {
                val id = (v as? Double)?.toInt() ?: throw Invalid("bad id for '$k'")
                if (words.getOrNull(id) != k) throw Invalid("word2idx and idx2word disagree for '$k'")
                map[k as String] = id
            }
            fun sp(name: String, token: String): Int {
                val id = (special[name] as? Double)?.toInt() ?: throw Invalid("missing special token $name")
                if (id !in 0 until declared) throw Invalid("special token $name = $id is outside the vocabulary")
                if (words[id] != token) throw Invalid("special token $name = $id is '${words[id]}', expected '$token'")
                return id
            }
            return ComerVocab(Array(declared) { words[it]!! }, map, sp("pad", "<pad>"), sp("sos", "<sos>"), sp("eos", "<eos>"))
        }
    }
}

enum class ComerMode { AUTO, NUMBER, EXPRESSION }

object ComerModes {
    /** Tokens allowed in number mode: digits, basic operators, \frac and structural tokens, plus the specials. Resolved by STRING, never by id. */
    private val numberTokens = listOf("(", ")", "+", "-", ".", "/", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "=", "\\div", "\\frac", "\\pm", "\\times", "^", "_", "{", "}")

    fun numberModeTokens(v: ComerVocab): Set<Int> = buildSet {
        add(v.pad); add(v.sos); add(v.eos)
        numberTokens.forEach { t -> v.id(t)?.let { add(it) } }
    }
}
