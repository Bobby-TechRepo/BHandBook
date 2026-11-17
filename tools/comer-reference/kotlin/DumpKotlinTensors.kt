import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.core.comer.*
import java.io.File
import java.util.Base64

fun main(args: Array<String>) {
    val src = File(args[0]); val out = File(args[1]); out.mkdirs()
    var n = 0
    for (f in src.listFiles()!!.filter { it.name.endsWith(".json") && !it.name.startsWith("junk") }.sortedBy { it.name }) {
        val sample = InkSampleCodec.decode(f.readText())!!
        for (size in listOf(0.4f, 1f, 2.5f, 5f)) {
            val strokes = sample.strokes.map { s -> s.copy(points = s.points.map { it.copy(x = it.x * size, y = it.y * size) }, width = s.width * size) }
            if (!ComerStrokeAdapter.isMeaningful(strokes)) { println("NOT MEANINGFUL: ${f.name}@$size"); continue }
            val r = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(strokes))
            val u8 = ByteArray(r.tensor.size) { Math.round(r.tensor[it] * 255f).toByte() }
            File(out, "${f.nameWithoutExtension}@$size.json").writeText(
                """{"label":${"\"" + sample.label.replace("\\", "\\\\").replace("\"", "\\\"") + "\""},"h":${r.height},"w":${r.width},"tensor":"${Base64.getEncoder().encodeToString(u8)}","mask":"${Base64.getEncoder().encodeToString(r.mask)}"}""")
            n++
        }
    }
    println("dumped $n Kotlin-preprocessed tensors")
}
