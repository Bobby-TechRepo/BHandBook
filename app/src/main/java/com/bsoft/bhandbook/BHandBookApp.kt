package com.bsoft.bhandbook

import android.app.Application
import android.content.ComponentCallbacks2
import com.bsoft.bhandbook.data.recognition.comer.ComerRuntime
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BHandBookApp : Application() {
    @Inject lateinit var comer: ComerRuntime
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { runCatching { comer.warmUp() }.onFailure { android.util.Log.e("BHandBookApp", "model warm-up failed; the built-in recogniser will be used", it) } }          // load the models in the background; never on the main thread
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) appScope.launch { comer.shutdown() }   // reloaded lazily when needed
    }
}
