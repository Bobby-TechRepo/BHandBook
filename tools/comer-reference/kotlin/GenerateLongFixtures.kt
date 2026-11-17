import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.InkSample
import com.bsoft.bhandbook.domain.model.Stroke
import java.io.File

/** label = the expected LaTeX with all spaces removed (so it can be compared with any tokenisation). */
fun main(args: Array<String>) {
    val out = File(args[0]); out.mkdirs()
    fun p(s: Long) = Pen(s, size = 80f, sloppiness = 0.5f)
    val defs: List<Pair<Pair<String, String>, (Long) -> List<Stroke>>> = listOf(
        ("len08_123+456" to "123+456") to { s -> p(s).text("123+456").strokes },
        ("len09_1234+5678" to "1234+5678") to { s -> p(s).text("1234+5678").strokes },
        ("len13_12*34-56+78" to "12\\times34-56+78") to { s -> p(s).text("12*34-56+78").strokes },
        ("len15_12345678+87654321" to "12345678+87654321") to { s -> p(s).text("12345678+87654321").strokes },
        ("len17_paren" to "(12+34)\\times(56-78)") to { s -> p(s).text("(12+34)*(56-78)").strokes },
        ("len20_long_sum" to "12+34+56+78+90+12+34=") to { s -> p(s).text("12+34+56+78+90+12+34=").strokes },
        ("len27_very_long" to "123+456+789+123+456+789+123+456") to { s -> p(s).text("123+456+789+123+456+789+123+456").strokes },
        ("len35_extreme" to "1234567+1234567+1234567+1234567+1234567") to { s -> p(s).text("1234567+1234567+1234567+1234567+1234567").strokes },
        ("frac_sum" to "\\frac{12}{34}+\\frac{1}{2}") to { s -> p(s).frac("12", "34").text("+").frac("1", "2").strokes },
        ("frac_in_eq" to "3+\\frac{12}{4}=") to { s -> p(s).text("3+").frac("12", "4").text("=").strokes },
        ("sqrt_sum" to "\\sqrt{2025}+10") to { s -> p(s).sqrt("2025").text("+10").strokes },
        ("decimals" to "3.14\\times2.5=") to { s -> p(s).text("3.14*2.5=").strokes },
        ("power_chain" to "2^{10}+3^{2}") to { s -> p(s).text("2").sup("10").text("+3").sup("2").strokes },
        ("mixed_two_lines_apart" to "100-25\\times3+8/2") to { s -> p(s).text("100-25*3+8/2").strokes },
    )
    for ((meta, gen) in defs) for (seed in 1L..3L) File(out, "${meta.first}_w$seed.json").writeText(InkSampleCodec.encode(InkSample(meta.second, null, gen(seed), 0L)))
    println("wrote ${out.listFiles()!!.size} long fixtures")
}
