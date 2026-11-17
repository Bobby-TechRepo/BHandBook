package com.bsoft.bhandbook.data.repository

import android.content.Context
import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.di.IoDispatcher
import com.bsoft.bhandbook.domain.model.InkSample
import com.bsoft.bhandbook.domain.repository.SampleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes one JSON file per sample to the app's external files directory
 * (`/sdcard/Android/data/<package>/files/samples/`, retrievable with `adb pull`), falling back to internal storage.
 */
@Singleton
class FileSampleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SampleRepository {
    override suspend fun save(sample: InkSample): String? = withContext(io) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "samples").apply { mkdirs() }
            val file = File(dir, "sample_${sample.createdAt}_${UUID.randomUUID().toString().take(8)}.json")
            file.writeText(InkSampleCodec.encode(sample))
            file.absolutePath
        }.getOrNull()
    }
}
