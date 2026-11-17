import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.InkSample
import java.io.File
fun main(args: Array<String>) {
    val out = File(args[0]); out.mkdirs()
    fun p(s: Long) = Pen(s, size = 80f, sloppiness = 0.5f)
    for (seed in 1L..3L) {
        File(out, "gap_power_w$seed.json").writeText(InkSampleCodec.encode(InkSample("2^{10}+3^{2}", null, p(seed).text("2").sup("10").gap(0.5f).text("+3").sup("2").strokes, 0L)))
        File(out, "gap_sqrt_w$seed.json").writeText(InkSampleCodec.encode(InkSample("\\sqrt{2025}+10", null, p(seed).sqrt("2025").gap(0.5f).text("+10").strokes, 0L)))
        File(out, "gap_decimals_w$seed.json").writeText(InkSampleCodec.encode(InkSample("3.14\\times2.5=", null, p(seed).text("3.14").gap(0.3f).text("*").gap(0.3f).text("2.5=").strokes, 0L)))
        File(out, "gap_eq_long_w$seed.json").writeText(InkSampleCodec.encode(InkSample("12+34+56+78+90+12+34=", null, p(seed).text("12+34+56+78+90+12+34").gap(0.4f).text("=").strokes, 0L)))
    }
}
