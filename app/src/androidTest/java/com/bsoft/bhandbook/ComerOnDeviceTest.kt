package com.bsoft.bhandbook

import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.core.comer.ComerMathRecognitionEngine
import com.bsoft.bhandbook.core.comer.ComerRecognition
import com.bsoft.bhandbook.core.comer.ComerRecognizer
import com.bsoft.bhandbook.core.comer.ComerRecognizerConfig
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.data.recognition.comer.ComerRuntime
import com.bsoft.bhandbook.data.recognition.comer.ComerSessionTuning
import com.bsoft.bhandbook.data.recognition.comer.OnnxComerBackend
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import com.bsoft.bhandbook.domain.usecase.ProcessExpressionUseCase
import com.bsoft.bhandbook.domain.usecase.ProcessingOutcome
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the REAL bundled CoMER models through ONNX Runtime Android. Requires scripts/fetch_comer_models.py to have been run before building.
 * Fixtures (stroke JSON written by the project's handwriting generator) and their expected output come from the reference ink-on pipeline.
 */
@RunWith(AndroidJUnit4::class)
class ComerOnDeviceTest {
    /** Every test is bounded: a slow target must produce a failure with a message, never an endless spinner. */
    @get:Rule val timeout: Timeout = Timeout(8, TimeUnit.MINUTES)

    private fun deviceInfo() = "${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}, abis ${Build.SUPPORTED_ABIS.toList()}, " +
        "cores ${Runtime.getRuntime().availableProcessors()}, hardware ${Build.HARDWARE}, emulator=${Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic")}"

    /** Full repetition count on a normal device, the minimum on a very slow one (e.g. an emulator without acceleration); the log says which. */
    private fun repetitions(r: ComerRecognizer, probe: List<Stroke>, normal: Int, minimum: Int): Int {
        val t0 = System.nanoTime(); r.recognize(probe); val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("ComerTest", "speed probe: $ms ms for one recognition on ${deviceInfo()}")
        return if (ms > 3_000) { Log.w("ComerTest", "SLOW TARGET: using $minimum repetitions instead of $normal"); minimum } else normal
    }

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext
    private val parser = DefaultMathParser()
    private fun fixture(name: String): List<Stroke> =
        InkSampleCodec.decode(instr.context.assets.open("comer/$name.json").bufferedReader().use { it.readText() })!!.strokes

    // expected application-level text for each specified expression (the reference decodes the same LaTeX for all three writers)
    private val expected = linkedMapOf(
        "add_2_3" to "2+3", "add_25_15" to "25+15", "mul_12_8" to "12×8", "div_15_3" to "15÷3", "sub_7_4" to "7-4",
        "pow_2_2" to "2^(2)", "frac_3_4" to "((3)/(4))", "sqrt_144" to "√(144)", "eq_2x" to "2x+5=15", "add_eq" to "25+15=",
    )

    private fun backend() = OnnxComerBackend.create(app.assets)

    companion object {
        /**
         * ONE session for the whole class. A fresh ONNX Runtime session can have a large one-off first-run cost (60 s was measured on one phone),
         * so creating a session per test made the suite look hung. Tests that are about creating/closing sessions make their own.
         */
        private lateinit var shared: OnnxComerBackend

        @JvmStatic @BeforeClass fun openSharedSession() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val t0 = System.nanoTime()
            shared = OnnxComerBackend.create(ctx.assets)
            Log.i("ComerTest", "shared session created in ${(System.nanoTime() - t0) / 1_000_000} ms; running the first (warm-up) recognition...")
            val stroke = InkSampleCodec.decode(InstrumentationRegistry.getInstrumentation().context.assets.open("comer/add_25_15_w1.json").bufferedReader().use { it.readText() })!!.strokes
            val t1 = System.nanoTime()
            val r = ComerRecognizer(shared, DefaultMathParser(), ComerRecognizerConfig()).recognize(stroke) as ComerRecognition.Recognized
            Log.i("ComerTest", "first recognition on a fresh session: ${r.timings} (${(System.nanoTime() - t1) / 1_000_000} ms wall)")
        }

        @JvmStatic @AfterClass fun closeSharedSession() { if (::shared.isInitialized) shared.close() }
    }
    private fun recognizer(b: OnnxComerBackend, beam: Int = 3) = ComerRecognizer(b, parser, ComerRecognizerConfig(beamWidth = beam))

    @Test fun deviceSpeed_reportsHowLongOneRecognitionTakes() {
        Log.i("ComerTest", "device: ${deviceInfo()}")
        backend().use { b ->
            Log.i("ComerTest", "model load ${b.info.initMs} ms with ${b.info.intraOpThreads} threads")
            val r = recognizer(b)
            for (i in 1..3) {
                val res = r.recognize(fixture("add_25_15_w1")) as ComerRecognition.Recognized
                Log.i("ComerTest", "run $i: ${res.timings} (${res.latex})")
            }
            val ms = (r.recognize(fixture("add_25_15_w1")) as ComerRecognition.Recognized).timings.totalMs
            assertTrue("one recognition takes $ms ms on ${deviceInfo()}. Under ~2 s is normal on a phone; minutes means an emulator without hardware acceleration " +
                "or with a CPU image that does not match the host: use an x86_64 image on an x86 host (or arm64 on Apple silicon) with acceleration enabled.", ms < 60_000)
        }
    }

    @Test fun modelFilesArePackagedWithTheApp_soNothingIsDownloaded() {
        val files = app.assets.list("models/comer")?.toSet().orEmpty()
        assertTrue("run scripts/fetch_comer_models.py, then rebuild (found $files)", files.containsAll(setOf("encoder_int8.onnx", "decoder_int8.onnx", "vocab.json")))
    }

    @Test fun backendLoadsBothModels_andTheirInterfaceMatchesTheManifest() {
        backend().use { b ->
            Log.i("ComerTest", "init ${b.info.initMs} ms; ${b.info}")
            assertEquals(113, b.vocab.size); assertEquals(113, b.info.vocabSize)
            assertEquals(setOf("pixel_values", "pixel_mask"), b.info.encoderInputs.keys)
            assertEquals(setOf("encoder_features", "encoder_mask", "input_ids"), b.info.decoderInputs.keys)
            assertEquals(setOf("logits"), b.info.decoderOutputs.keys)
            assertTrue("initialisation took ${b.info.initMs} ms", b.info.initMs < 15_000)
        }
    }

    @Test fun recognisesTheSpecifiedExpressions_fromThreeWriters_andReportsAccuracy() {
        var ok = 0; var total = 0; val misses = ArrayList<String>(); val times = ArrayList<Long>()
        run { val b = shared
            val r = recognizer(b)
            val writers = repetitions(r, fixture("add_25_15_w1"), normal = 3, minimum = 1)
            for ((name, want) in expected) for (w in 1..writers) {
                Log.i("ComerTest", "recognising ${name}_w$w")
                val res = r.recognize(fixture("${name}_w$w")); total++
                val got = (res as? ComerRecognition.Recognized)?.linear
                if (got == want) ok++ else misses += "${name}_w$w expected '$want' got '${got ?: res}'"
                (res as? ComerRecognition.Recognized)?.let { times += it.timings.totalMs }
            }
        }
        Log.i("ComerTest", "accuracy $ok/$total; avg recognise ${times.average().toInt()} ms; misses: $misses")
        assertTrue("accuracy $ok/$total: $misses", ok >= total * 9 / 10)     // total adapts to the number of writers actually run       // reference pipeline gets 30/30 on these fixtures; allow numeric drift on other CPUs
    }

    @Test fun longExpressions_useGreedyDecoding_dropTheSpuriousTailAfterEquals_andVeryLongOnesAreNotTrusted() {
        run { val b = shared
            val r = recognizer(b)
            val sum = r.recognize(fixture("len20_long_sum_w1")) as ComerRecognition.Recognized
            Log.i("ComerBenchmark", "len20 (${sum.latex.split(" ").size} tokens): ${sum.timings} adjustments=${sum.adjustments}")
            assertEquals("12+34+56+78+90+12+34=", sum.linear)                       // the model alone emits a stray '1' after the '='
            assertEquals("12×34-56+78", (r.recognize(fixture("len13_mixed_w1")) as ComerRecognition.Recognized).linear)
            val extreme = r.recognize(fixture("len35_extreme_w1"))
            assertTrue("a 36-token single line is squeezed by the width cap and must ask for confirmation",
                extreme is ComerRecognition.Recognized && extreme.warnings.isNotEmpty() || extreme is ComerRecognition.NeedsCorrection)
        }
    }

    @Test fun meaninglessInk_doesNotRunInference() {
        run { val b = shared
            val start = System.nanoTime()
            assertTrue(recognizer(b).recognize(fixture("junk_dot")) is ComerRecognition.NotMeaningful)
            assertTrue("rejected without model work", (System.nanoTime() - start) / 1_000_000 < 50)
        }
    }

    @Test fun repeatedRecognition_isDeterministic_andDoesNotLeakNativeMemory() {
        run { val b = shared
            val r = recognizer(b); val ink = fixture("add_25_15_w1")
            val first = (r.recognize(ink) as ComerRecognition.Recognized).latex
            val runs = repetitions(r, ink, normal = 40, minimum = 6)
            System.gc(); val before = Debug.getNativeHeapAllocatedSize()
            repeat(runs) { i -> Log.i("ComerTest", "repeat ${i + 1}/$runs"); assertEquals(first, (r.recognize(ink) as ComerRecognition.Recognized).latex) }
            System.gc(); val growthMb = (Debug.getNativeHeapAllocatedSize() - before) / 1_048_576.0
            Log.i("ComerTest", "native heap growth over $runs recognitions: ${"%.1f".format(growthMb)} MB")
            assertTrue("native memory grew by $growthMb MB (tensor leak?)", growthMb < 25.0)
        }
    }

    @Test fun benchmark_timingsPerBeamWidth() {
        run { val b = shared
            val ink = fixture("eq_2x_w1")
            val perBeam = repetitions(recognizer(b, 1), ink, normal = 8, minimum = 2)
            for (beam in 1..3) {
                val r = recognizer(b, beam); r.recognize(ink)                               // warm-up
                val runs = (1..perBeam).map { r.recognize(ink) as ComerRecognition.Recognized }
                val t = runs.map { it.timings }
                Log.i("ComerBenchmark", "beam=$beam preprocess=${t.map { it.preprocessMs }.average().toInt()}ms encoder=${t.map { it.encoderMs }.average().toInt()}ms " +
                    "decoder=${t.map { it.decoderMs }.average().toInt()}ms total=${t.map { it.totalMs }.average().toInt()}ms  -> ${runs.first().latex}")
                assertTrue("beam $beam is too slow: ${t.map { it.totalMs }}", t.map { it.totalMs }.average() < 10_000)
            }
        }
    }

    @Test fun twoThreadsRecognisingAtOnce_areSerialisedSafely() {
        run { val b = shared
            val engine = ComerMathRecognitionEngine(recognizer(b)); val done = CountDownLatch(2); val results = java.util.Collections.synchronizedList(ArrayList<String>())
            repeat(2) { n -> Thread { results += engine.recognize(fixture(if (n == 0) "add_25_15_w1" else "mul_12_8_w1")).expression.linear; done.countDown() }.start() }
            assertTrue(done.await(60, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(setOf("25+15", "12×8"), results.toSet())
        }
    }

    @Test fun runtime_canShutDownAndTransparentlyReload() {
        val runtime = ComerRuntime(app, parser)
        val engine = runtime.engine(); assertNotNull("CoMER failed to start: ${runtime.status}", engine)
        assertEquals("25+15", engine!!.recognize(fixture("add_25_15_w1")).expression.linear)
        runtime.shutdown()
        assertEquals("25+15", runtime.engine()!!.recognize(fixture("add_25_15_w1")).expression.linear)     // reloaded lazily
        runtime.shutdown()
    }

    @Test fun endToEnd_inkToCalculation_throughTheExistingPipeline() {
        val useCase = ProcessExpressionUseCase(ComerMathRecognitionEngine(recognizer(shared)), parser, ExactCalculationEngine(), DefaultSolutionEngine())
        val sum = useCase(fixture("add_eq_w1")) as ProcessingOutcome.Evaluated
        assertEquals("40", sum.result); assertTrue("'=' is an explicit request", sum.explicit); assertEquals("25 + 15", sum.displayText)
        val mul = useCase(fixture("mul_12_8_w1")) as ProcessingOutcome.Evaluated; assertEquals("96", mul.result)
        val frac = useCase(fixture("frac_3_4_w1")) as ProcessingOutcome.Evaluated; assertEquals("3/4", frac.result)
        val root = useCase(fixture("sqrt_144_w1")) as ProcessingOutcome.Evaluated; assertEquals("12", root.result)
        val eq = useCase(fixture("eq_2x_w1")) as ProcessingOutcome.Solved; assertEquals("x = 5", eq.finalExpression); assertEquals(4, eq.steps.size)
    }

    /**
     * Diagnoses a slow first run. Each configuration gets its own session; for each it logs the session creation time, the first
     * recognition (new input shape), a repeat (same shape) and a different input shape. Read the "ComerProbe" lines in logcat.
     */
    @Test fun firstRunProbe_comparesSessionSettings() {
        val configs = listOf(
            "ORT defaults" to ComerSessionTuning(),
            "1 thread" to ComerSessionTuning(intraOpThreads = 1),
            "memory pattern off" to ComerSessionTuning(memoryPatternOptimization = false),
            "arena + pattern off" to ComerSessionTuning(memoryPatternOptimization = false, cpuArena = false),
            "no graph optimisation" to ComerSessionTuning(optimizationLevel = ai.onnxruntime.OrtSession.SessionOptions.OptLevel.NO_OPT),
        )
        for ((label, tuning) in configs) {
            val t0 = System.nanoTime()
            OnnxComerBackend.create(app.assets, tuning).use { b ->
                val create = (System.nanoTime() - t0) / 1_000_000
                val r = recognizer(b, 1)
                fun run(name: String): String { val t = System.nanoTime(); val res = r.recognize(fixture(name)) as ComerRecognition.Recognized; return "${(System.nanoTime() - t) / 1_000_000} ms (enc ${res.timings.encoderMs}, dec ${res.timings.decoderMs})" }
                val first = run("add_25_15_w1"); val repeat = run("add_25_15_w1"); val other = run("eq_2x_w1"); val another = run("sqrt_144_w1")
                Log.i("ComerProbe", "[$label] $tuning | create $create ms | first $first | repeat $repeat | different shape $other | another shape $another")
            }
        }
    }

    @Test fun theAppDoesNotRequestInternetAccess_soRecognitionCannotUseTheNetwork() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
    }
}
