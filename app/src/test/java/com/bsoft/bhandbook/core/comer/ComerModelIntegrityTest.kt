package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.codec.MiniJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Keeps the documented provenance (docs/comer/model-manifest.json), the fetch script's pins and the real files in agreement. */
class ComerModelIntegrityTest {
    private fun root(): File? = listOfNotNull(System.getenv("PROJECT_APP_DIR")?.let { File(it).parentFile }, File("."), File("..")).firstOrNull { File(it, "scripts/fetch_comer_models.py").exists() }
    private fun manifest(): Map<String, Any?> {
        val f = File(root()!!, "docs/comer/model-manifest.json")
        assertTrue("docs/comer/model-manifest.json must exist", f.exists())
        return ComerTestData.map(MiniJson.parse(f.readText()))
    }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun manifestRecordsSourceReleaseCommitAndLicence() {
        assumeTrue("project root not found", root() != null)
        val m = manifest()
        assertEquals("v0.1.0", m["release"]); assertEquals("aaf79ca3722458cd8bf8f38de26a32d0e0b44d68", m["sourceCommit"])
        assertTrue((m["source"] as String).contains("kimseungdae/ink-on"))
        val lic = ComerTestData.map(m["license"]); assertEquals("Apache-2.0", lic["repository"])
        assertEquals("NOT_ESTABLISHED", lic["weightsRedistributionStatus"])
        assertEquals(113.0, ComerTestData.map(m["vocabulary"])["size"])
    }
    @Test fun manifestChecksums_matchTheFetchScriptPins() {
        assumeTrue("project root not found", root() != null)
        val script = File(root()!!, "scripts/fetch_comer_models.py").readText()
        val files = ComerTestData.map(manifest()["files"])
        for ((name, v) in files) for (h in ComerTestData.strings(ComerTestData.map(v)["sha256"])) assertTrue("$name $h is not pinned in fetch_comer_models.py", script.contains(h))
        assertEquals(setOf("encoder_int8.onnx", "decoder_int8.onnx", "vocab.json"), files.keys)
    }
    @Test fun manifestInterface_matchesWhatTheAndroidBackendVerifiesAtLoadTime() {
        assumeTrue("project root not found", root() != null)
        val g = ComerTestData.map(manifest()["graphs"])
        val enc = ComerTestData.map(g["encoder_int8.onnx"]); val dec = ComerTestData.map(g["decoder_int8.onnx"])
        assertEquals(setOf("pixel_values", "pixel_mask"), ComerTestData.map(enc["inputs"]).keys)
        assertEquals(setOf("encoder_features", "encoder_mask"), ComerTestData.map(enc["outputs"]).keys)
        assertEquals(setOf("encoder_features", "encoder_mask", "input_ids"), ComerTestData.map(dec["inputs"]).keys)
        assertEquals(setOf("logits"), ComerTestData.map(dec["outputs"]).keys)
        val backend = File(root()!!, "app/src/main/java/com/bsoft/bhandbook/data/recognition/comer/OnnxComerBackend.kt").readText()
        for (n in listOf("pixel_values", "pixel_mask", "encoder_features", "encoder_mask", "input_ids", "logits")) assertTrue("backend does not reference $n", backend.contains("\"$n\""))
    }
    @Test fun modelFilesPresentOnDisk_areTheExactPinnedReleaseArtifacts() {
        assumeTrue("project root not found", root() != null)
        val dirs = listOfNotNull(System.getenv("COMER_MODELS_DIR"), File(root()!!, "app/src/main/assets/models/comer").path).map { File(it) }
        val dir = dirs.firstOrNull { File(it, "encoder_int8.onnx").exists() }; assumeTrue("model files not fetched", dir != null)
        for ((name, v) in ComerTestData.map(manifest()["files"])) {
            val info = ComerTestData.map(v); val bytes = File(dir!!, name).readBytes()
            assertTrue("$name sha256 ${sha256(bytes)} is not a pinned digest", sha256(bytes) in ComerTestData.strings(info["sha256"]))
            if (name.endsWith(".onnx")) assertEquals("$name size", (info["bytes"] as Double).toLong(), bytes.size.toLong())
        }
    }
    @Test fun theProjectDoesNotShipTheModelBinaries_untilTheirLicenceChainIsSettled() {
        assumeTrue("project root not found", root() != null)
        val shipped = File(root()!!, "app/src/main/assets/models/comer").listFiles()?.filter { it.extension == "onnx" }.orEmpty()
        val gitignore = File(root()!!, ".gitignore").takeIf { it.exists() }?.readText().orEmpty()
        assertTrue("*.onnx must be git-ignored", gitignore.contains("*.onnx") || shipped.isEmpty())
    }
}
