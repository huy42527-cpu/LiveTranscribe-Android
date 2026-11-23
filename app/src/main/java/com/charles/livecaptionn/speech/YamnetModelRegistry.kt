package com.charles.livecaptionn.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads and atomically installs the optional 4.1 MB YAMNet model. */
class YamnetModelRegistry(context: Context) {
    private val target = File(context.applicationContext.filesDir, "audio/yamnet/yamnet.tflite")
    private val staging = File(target.parentFile, ".yamnet.tflite.tmp")
    private val backup = File(target.parentFile, ".yamnet.tflite.backup")
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mutableDownloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = mutableDownloading.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun isInstalled(): Boolean = target.isFile && target.length() == MODEL_BYTES &&
        sha256(target) == MODEL_SHA256

    fun modelFile(): File? = target.takeIf { isInstalled() }

    suspend fun downloadAndInstall(): Result<File> = withContext(Dispatchers.IO) {
        mutableDownloading.value = true
        mutableError.value = null
        try {
            target.parentFile?.mkdirs()
            client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("YAMNet download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw java.io.IOException("YAMNet download was empty")
                FileOutputStream(staging).use { output ->
                    body.byteStream().use { it.copyTo(output) }
                }
            }
            if (staging.length() != MODEL_BYTES || sha256(staging) != MODEL_SHA256) {
                throw java.io.IOException("YAMNet model checksum/size validation failed")
            }
            if (backup.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                throw java.io.IOException("Could not preserve existing YAMNet model")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw java.io.IOException("Could not install YAMNet model")
            }
            backup.delete()
            Result.success(target)
        } catch (t: Throwable) {
            Log.w(TAG, "YAMNet model installation failed", t)
            staging.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            mutableError.value = t.message ?: "YAMNet download failed"
            Result.failure(t)
        } finally {
            mutableDownloading.value = false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        private const val TAG = "YamnetModelRegistry"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/MediaPipe-AudioClassifier-YAMNet/resolve/main/yamnet.tflite"
        private const val MODEL_BYTES = 4_126_810L
        private const val MODEL_SHA256 =
            "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
    }
}
