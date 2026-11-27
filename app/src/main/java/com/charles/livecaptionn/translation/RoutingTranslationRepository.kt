package com.charles.livecaptionn.translation

import java.util.logging.Logger
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.TranslationBackend
import kotlinx.coroutines.flow.first

/**
 * Dispatches each translation call to the backend selected in user settings.
 *
 * The selected delegate is resolved per request. This avoids a shared mutable
 * backend/delegate cache becoming inconsistent when partial and final
 * translations run concurrently while settings change.
 */
class RoutingTranslationRepository(
    private val settingsRepository: SettingsRepository,
    private val mlKit: TranslationRepository,
    private val libre: TranslationRepository,
    private val googleWeb: TranslationRepository
) : TranslationRepository {

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val backend = settingsRepository.settingsFlow.first().translationBackend
        val delegate = delegateFor(backend)
        logger.fine("Dispatching translation: backend=$backend, source=$sourceCode, target=$targetCode, autoDetect=$autoDetect.")
        return try {
            delegate.translate(text, sourceCode, targetCode, autoDetect).also { result ->
                logger.fine("Translation backend completed: backend=$backend, blank=${result.isBlank()}, resultLength=${result.length}.")
            }
        } catch (error: Exception) {
            logger.warning("Translation backend threw: backend=$backend, error=${error.javaClass.simpleName}.")
            throw error
        }
    }

    override suspend fun prewarm(sourceCode: String, targetCode: String) {
        val backend = settingsRepository.settingsFlow.first().translationBackend
        logger.fine("Prewarming translation backend: backend=$backend.")
        delegateFor(backend).prewarm(sourceCode, targetCode)
    }

    private fun delegateFor(backend: TranslationBackend): TranslationRepository =
        when (backend) {
            TranslationBackend.ML_KIT -> mlKit
            TranslationBackend.LIBRE_TRANSLATE -> libre
            TranslationBackend.GOOGLE_WEB -> googleWeb
        }

    override fun close() {
        mlKit.close()
        libre.close()
        googleWeb.close()
    }

    private companion object {
        val logger: Logger = Logger.getLogger("TranslationRouter")
    }
}
