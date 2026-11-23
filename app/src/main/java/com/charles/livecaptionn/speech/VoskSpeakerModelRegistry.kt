package com.charles.livecaptionn.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Installs the optional Vosk speaker model separately from language models.
 * The target directory is replaced only after the downloaded archive passes
 * structural validation.
 */
class VoskSpeakerModelRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val targetDir = File(appContext.filesDir, "vosk/speaker/vosk-model-spk-0.4")
    private val cacheDir = File(appContext.cacheDir, "vosk-speaker-downloads")
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun isInstalled(): Boolean = hasRequiredFiles(targetDir)

    fun resolveModelDir(): File? = targetDir.takeIf(::hasRequiredFiles)

    suspend fun downloadAndInstall(): Result<File> = withContext(Dispatchers.IO) {
        val zip = File(cacheDir, "vosk-model-spk-0.4.zip")
        val staging = File(targetDir.parentFile, ".staging-vosk-model-spk-0.4")
        val backup = File(targetDir.parentFile, ".backup-vosk-model-spk-0.4")
        try {
            cacheDir.mkdirs()
            targetDir.parentFile?.mkdirs()
            client.newCall(
                Request.Builder().url(MODEL_URL).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Speaker model download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw java.io.IOException("Speaker model download was empty")
                FileOutputStream(zip).use { output -> body.byteStream().use { it.copyTo(output) } }
            }

            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            extractSafely(zip, staging)
            val source = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
            if (!hasRequiredFiles(source)) {
                throw java.io.IOException("Downloaded speaker model is incomplete")
            }

            File(source, ".installed").writeText("ok")
            if (backup.exists()) backup.deleteRecursively()
            if (targetDir.exists() && !targetDir.renameTo(backup)) {
                throw java.io.IOException("Could not preserve the previous speaker model")
            }

            val installed = if (source == staging) {
                staging.renameTo(targetDir)
            } else {
                source.renameTo(targetDir)
            }
            if (!installed) {
                if (backup.exists()) backup.renameTo(targetDir)
                throw java.io.IOException("Could not install the speaker model")
            }

            backup.deleteRecursively()
            staging.deleteRecursively()
            zip.delete()
            Result.success(targetDir)
        } catch (t: Throwable) {
            Log.w(TAG, "Speaker model installation failed", t)
            if (!targetDir.exists() && backup.exists()) backup.renameTo(targetDir)
            staging.deleteRecursively()
            zip.delete()
            Result.failure(t)
        }
    }

    private fun extractSafely(zip: File, destination: File) {
        val root = destination.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val output = File(destination, entry.name)
                val canonical = output.canonicalFile
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                    throw java.io.IOException("Unsafe path in speaker model archive")
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { input.copyTo(it) }
                }
                input.closeEntry()
            }
        }
    }

    private fun hasRequiredFiles(directory: File): Boolean {
        if (!directory.isDirectory) return false
        return REQUIRED_FILES.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        }
    }

    companion object {
        private const val TAG = "VoskSpeakerModelRegistry"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
        private val REQUIRED_FILES = listOf(
            "mfcc.conf",
            "final.ext.raw",
            "mean.vec",
            "transform.mat"
        )
    }
}
