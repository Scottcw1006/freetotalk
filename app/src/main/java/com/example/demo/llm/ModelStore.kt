package com.example.demo.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Models ship inside the APK, but MediaPipe wants a real filesystem path and an asset is
 * not one. Each model is copied out once on first use; later launches only verify the
 * copy is intact.
 */
object ModelStore {

    /** A model whose .task file was never added to assets/ is simply not offered. */
    fun isBundled(context: Context, spec: ModelSpec): Boolean =
        runCatching { context.assets.list("").orEmpty().contains(spec.assetName) }
            .getOrDefault(false)

    fun installed(context: Context): List<ModelSpec> =
        ModelSpec.entries.filter { isBundled(context, it) }

    fun modelFile(context: Context, spec: ModelSpec): File =
        File(context.filesDir, spec.assetName)

    suspend fun ensureExtracted(
        context: Context,
        spec: ModelSpec,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(context, spec)
        // openFd only works because the build declares noCompress += "task".
        val expectedSize = context.assets.openFd(spec.assetName).use { it.length }
        if (target.length() == expectedSize) {
            onProgress(1f)
            return@withContext target
        }

        val partial = File(context.filesDir, "${spec.assetName}.part")
        partial.delete()
        context.assets.open(spec.assetName).use { source ->
            partial.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    copied += read
                    onProgress(copied.toFloat() / expectedSize)
                }
            }
        }

        target.delete()
        check(partial.renameTo(target)) { "無法把模型搬到 ${target.absolutePath}" }
        target
    }
}
