import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.InkSample
import java.io.File

fun main(args: Array<String>) {
    val out = File(args[0]); out.mkdirs()
    // label is the LaTeX we expect; writer = seed
    val defs: List<Triple<String, String, (Long) -> List<com.bsoft.bhandbook.domain.model.Stroke>>> = listOf(
        Triple("add_2_3", "2 + 3") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("2+3").strokes },
        Triple("add_25_15", "25 + 15") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("25+15").strokes },
        Triple("mul_12_8", "12 \\times 8") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("12*8").strokes },
        Triple("div_15_3", "15 \\div 3") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("15D3").strokes },
        Triple("sub_7_4", "7 - 4") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("7-4").strokes },
        Triple("pow_2_2", "2 ^ { 2 }") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("2").sup("2").strokes },
        Triple("frac_3_4", "\\frac { 3 } { 4 }") { s -> Pen(s, size = 80f, sloppiness = 0.5f).frac("3", "4").strokes },
        Triple("sqrt_144", "\\sqrt { 144 }") { s -> Pen(s, size = 80f, sloppiness = 0.5f).sqrt("144").strokes },
        Triple("eq_2x", "2 x + 5 = 15") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("2x+5=15").strokes },
        Triple("add_eq", "25 + 15 =") { s -> Pen(s, size = 80f, sloppiness = 0.5f).text("25+15=").strokes },
    )
    for ((name, label, gen) in defs) for (seed in 1L..3L) {
        File(out, "${name}_w$seed.json").writeText(InkSampleCodec.encode(InkSample(label, null, gen(seed), 0L)))
    }
    // inputs that must NOT reach the model
    File(out, "junk_dot.json").writeText(InkSampleCodec.encode(InkSample("", null, Pen(1).text(".").strokes, 0L)))
    println("wrote ${out.listFiles()!!.size} fixtures")
}
