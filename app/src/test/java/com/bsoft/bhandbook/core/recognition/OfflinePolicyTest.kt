package com.bsoft.bhandbook.core.recognition

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Static guarantees behind "recognition works completely offline": no network permission and no networking code in the app. */
class OfflinePolicyTest {
    private fun appDir(): File? = listOfNotNull(System.getenv("PROJECT_APP_DIR"), ".", "app").map { File(it) }.firstOrNull { File(it, "src/main/AndroidManifest.xml").exists() }

    @Test fun manifestDoesNotRequestInternetAccess() {
        val d = appDir(); assumeTrue("project dir not found", d != null)
        val manifest = File(d, "src/main/AndroidManifest.xml").readText()
        assertFalse("android.permission.INTERNET must not be requested", manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("ACCESS_NETWORK_STATE"))
    }
    @Test fun noMainSourceUsesNetworkingApis() {
        val d = appDir(); assumeTrue("project dir not found", d != null)
        val forbidden = listOf("java.net.", "javax.net.", "okhttp3", "retrofit2", "HttpURLConnection", "android.net.", "WebView", "ktor.client", "java.nio.channels.SocketChannel")
        val offenders = File(d, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (!line.trimStart().startsWith("*") && !line.trimStart().startsWith("/*") && forbidden.any { code.contains(it) }) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertTrue("networking code found:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
    @Test fun noRuntimeModelDownloadCodeExists() {
        val d = appDir(); assumeTrue("project dir not found", d != null)
        val comerSources = File(d, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.path.contains("comer", ignoreCase = true) }.toList()
        assertTrue(comerSources.isNotEmpty())
        for (f in comerSources) {
            val text = f.readText()
            assertFalse("${f.name} must not build URLs or download anything", Regex("""URL\s*\(|openConnection|downloadModel|fetchModel""").containsMatchIn(text.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")))
        }
    }
    @Test fun modelsAreLoadedFromAssets_notFromTheNetwork() {
        val d = appDir(); assumeTrue("project dir not found", d != null)
        val loader = File(d, "src/main").walkTopDown().firstOrNull { it.name == "OnnxComerBackend.kt" }
        assertNotNull("OnnxComerBackend.kt must exist", loader)
        assertTrue(loader!!.readText().contains("assets"))
    }
}
