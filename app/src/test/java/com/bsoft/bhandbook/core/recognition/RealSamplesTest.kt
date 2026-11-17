package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.domain.model.InkSample
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Scores the recogniser on REAL handwriting collected with the app (see src/test/resources/samples/README.md).
 * With no samples present it does nothing. With samples it prints the accuracy and every misread, and enforces
 * the one number that protects users: wrong answers shown without asking for confirmation must stay rare.
 */
class RealSamplesTest {
    private fun sampleFiles(): List<File> {
        val dir = RealSamplesTest::class.java.getResource("/samples")?.toURI()?.let { File(it) } ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("sample_") && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
    }

    @Test fun realHandwriting_report() {
        val files = sampleFiles()
        if (files.isEmpty()) { println("REAL SAMPLES: none found (add files to src/test/resources/samples to measure real accuracy)"); return }
        val decoded: List<InkSample?> = files.map { InkSampleCodec.decode(it.readText()) }
        assertTrue("unreadable sample files: " + files.filterIndexed { i, _ -> decoded[i] == null }.map { it.name }, decoded.all { it != null })
        val report = SampleEvaluator.evaluate(decoded.filterNotNull())
        println("REAL SAMPLES: " + report.summary())
        assertTrue("silent error rate ${"%.1f".format(report.silentErrorRate * 100)}% is too high:\n${report.summary()}", report.silentErrorRate <= 0.10)
    }
}
